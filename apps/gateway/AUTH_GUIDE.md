# 认证与授权整体流程笔记

> 面向团队成员的学习文档，记录当前 "game-hub" 中与 Keycloak 对接、登录、透传、登出、事件驱动以及黑名单的核心流程。

---

## 整体架构概览

```text
┌──────────────┐         ┌──────────────────────────┐
│   Browser    │  HTTPS  │ Spring Cloud Gateway      │
│ (前端页面)   ├────────►│ - OAuth2 Client           │
└──────┬───────┘         │ - 资源服务器 / 黑名单     │
       │                 │ - TokenRelay 透传 JWT     │
       │                 └─────────┬─────────────────┘
       │                           │
       │                           │REST / WS
       │                           ▼
       │                 ┌──────────────────────────┐
       │                 │ game-service             │
       │                 │ - REST & WebSocket 业务  │
       │                 └──────────────────────────┘
       │                           │
       │                           │REST
       │                           ▼
       │                 ┌──────────────────────────┐
       │                 │ system-service           │
       │                 │ - 用户/权限管理、Webhook │
       │                 └──────────────────────────┘
       │
       │
       │  OAuth2 / OIDC           ┌──────────────────┐
       └──────────────────────────► Keycloak         │
                                 │ (依赖 PostgreSQL) │
                                 └──────────────────┘

Redis (黑名单存储)  ◄───── Spring Cloud Gateway
```

1. **前端 Browser**：访问网关暴露的静态页面，所有受保护请求都会被重定向到 Keycloak 登录。
2. **Spring Cloud Gateway**：作为 OAuth2 Client 与 Resource Server，负责登录、token 透传、黑名单控制，是所有请求的入口。
3. **业务服务层**：`game-service` 处理游戏逻辑及 WebSocket；`system-service` 管理用户、权限并接收 Keycloak Webhook。
4. **认证中心与存储**：Keycloak 负责身份认证、事件推送并依赖 PostgreSQL；Redis 仅被 Gateway 用来保存已撤销 token 列表。

1. **Gateway**：负责 OAuth2 登录、token 透传、统一的黑名单与登出逻辑。
2. **game-service / system-service**：作为下游资源服务器，仅需校验 JWT 并处理业务。
3. **Keycloak**：提供用户/会话管理、事件推送、主题自定义；使用 Postgres 做持久化。
4. **Redis**：由网关用于记录撤销 token（黑名单）。

---

## 为什么选 Keycloak？

- **开源、成熟**：社区活跃、可定制性强，适合快速集成 OAuth2/OpenID Connect。
- **内置管理界面**：用户、客户端、角色、事件等可视化配置减少二次开发成本。
- **扩展能力**：支持事件 SPI、自定义主题、与企业目录对接，灵活扩展登录体验。
- **与 Spring 生态契合**：Spring Security 对 Keycloak 有直接的 OAuth2 Client/Resource Server 支持，整合成本低。

---

## 1. Keycloak 快速了解

- **Keycloak 是什么？** 一款开源的身份认证与访问管理平台，支持 OAuth2、OpenID Connect、SAML、社交登录等常见协议。
- **核心概念**：
  - **Realm**：逻辑隔离的认证域，类似租户；各 Realm 的用户、客户端互不影响。
  - **Client**：在 Keycloak 注册的可信应用，例如我们的 Gateway。
  - **User**：在 Realm 内维护的用户实体，可通过 UI 或 API 管理。
  - **Events**：Keycloak 记录的用户事件（登录、注册、登出等），可通过插件推送到外部系统。

Keycloak 默认使用数据库持久化（PostgreSQL、MySQL 等）。在本项目中：
- Postgres 运行在 docker compose 中（服务名 `postgres`）。
- `keycloak` 容器通过 `KC_DB=postgres`、`KC_DB_URL` 等环境变量连接同一 Postgres。
- 初始数据库可通过 `postgres` 服务的 `POSTGRES_DB` 设定；Keycloak 第一次启动会自行创建 schema。

---

## 2. 容器部署与基础配置

### 2.1 docker-compose 重点

1. **Keycloak 服务**定义在 `docker-compose.yml` 中，保留以下要点即可：
   - 环境变量中配置数据库、管理员账号、HTTP 端口。
   - 通过 `volumes` 将宿主机的 `D:/DockerData/Keycloak/providers` 映射到容器 `/opt/keycloak/providers`，用于放置插件。
   - `extra_hosts` 中追加 `"host.docker.internal:host-gateway"`，方便容器访问宿主机。
2. **Redis、Gateway** 的环境变量保持与本地一致即可，文档不再赘述。

### 2.2 Keycloak 客户端配置

| 位置 | 内容 |
| --- | --- |
| Realm settings → Login | 开启 `User registration`（否则注册按钮不会显示，也无法触发注册事件）；根据业务勾选 `Login with email` 等。 |
| Realm settings → Events | 在 `Event listeners` 中添加 `webhook-http`，并在 `User events settings` 里确保勾选 `REGISTER/LOGIN/LOGOUT` 等事件。 |
| Clients → game-hub → Settings | 设为 `confidential` 类型，配置 redirect/logout URI：`http://localhost:8080/login/oauth2/code/keycloak`、`http://localhost:8080/game-service/lobby.html`；在 `Valid post logout redirect URIs` 中加 `http://localhost:8080/*`；`Web origins` 建议填 `http://localhost:8080`。 |
| Clients → game-hub → Capability config | 勾选 `Standard flow` 与 `Direct access grants`（授权码登录 + 允许以凭证直接获取 token）；保持 `Client authentication`、`Authorization` 为 ON。 |
| Clients → game-hub → Logout settings | 打开 `Front channel logout` 和 `Backchannel logout`，保持默认 URL 为空即可（我们让 Gateway 处理登出）。 |

---

## 3. 项目代码如何接入 Keycloak

### 3.1 Gateway 模块

- 关键文件：`apps/gateway/src/main/java/com/gamehub/gateway/config/SecurityConfig.java`
- 主要职责：
  1. 通过 `spring-security` 的 OAuth2 Client 支持，与 Keycloak 建立授权码流程。
  2. 作为资源服务器验证下游请求携带的 JWT。
  3. 自定义登出行为，写入黑名单并调用 Keycloak 退出接口。

核心配置（节选）：

```java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                            ReactiveClientRegistrationRepository clientRegistrationRepository,
                                                            ReactiveJwtDecoder jwtDecoder,
                                                            JwtBlacklistService blacklistService,
                                                            ServerOAuth2AuthorizedClientRepository authorizedClientRepository) {
        http.csrf(csrf -> csrf.disable());

        http.authorizeExchange(ex -> ex
                .pathMatchers("/", "/actuator/**", "/token", "/logout").permitAll()
                .pathMatchers("/game-service/**").authenticated()
                .anyExchange().authenticated()
        );

        http.oauth2Login(Customizer.withDefaults());
        http.oauth2Client(Customizer.withDefaults());
        http.oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)));

        http.logout(l -> l.logoutHandler(jwtBlacklistLogoutHandler(blacklistService, authorizedClientRepository))
                          .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));
        return http.build();
    }
}
```

### 3.2 system-service & game-service

- 仅保留最小的 Resource Server 配置（`oauth2ResourceServer().jwt()`）即可。黑名单逻辑集中在 Gateway。

---

## 4. 登录 / 登出 / Token 透传

### 4.1 登录流程

1. 浏览器访问受保护资源 → Gateway 根据 `SecurityConfig` 判断未登录，重定向到 `/oauth2/authorization/keycloak`。
2. Keycloak 完成登录后回调 Gateway → Spring Security 保存授权信息。
3. 前端通过 `/token` 接口获取 access token（`TokenController` 会自动刷新 token）。
4. 前端在调用 `/game-service/**`、`/system-service/**` 接口时，先把 token 存储在 localStorage 并随请求发送。
5. `TokenRelay` Filter（配置在 `application.yml`）将 OAuth2 token 自动添加到下游请求头。

### 4.2 Token 透传代码

- `/token` 接口：`apps/gateway/src/main/java/com/gamehub/gateway/controller/TokenController.java`
  ```java
  @GetMapping("/token")
  public Mono<ResponseEntity<Map<String, Object>>> getToken(Authentication authentication) {
      if (authentication == null || !authentication.isAuthenticated()) {
          return Mono.just(ResponseEntity.status(401).body(Map.of("error", "未登录")));
      }
      OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
              .withClientRegistrationId("keycloak")
              .principal(authentication)
              .build();
      return authorizedClientManager.authorize(authorizeRequest)
              .map(authorizedClient -> ResponseEntity.ok(Map.of("access_token", authorizedClient.getAccessToken().getTokenValue())))
              .defaultIfEmpty(ResponseEntity.status(401).body(Map.of("error", "未找到授权客户端")));
  }
  ```

### 4.3 登出与黑名单

- 登出处理在 `jwtBlacklistLogoutHandler`：
  1. 读取当前授权的 access token。
  2. 写入黑名单（Redis）。
  3. 移除会话中的 OAuth2AuthorizedClient → 防止旧 token 刷新。
  4. 调 Keycloak 的 OIDC 退出端点。
- 黑名单存储：`JwtBlacklistService`。
- JWT 解码时检查黑名单：`JwtDecoderConfig`。

---

## 5. Keycloak 事件驱动（注册/登录等）

### 5.1 插件下载与部署

1. 从 [vymalo/keycloak-webhook](https://github.com/vymalo/keycloak-webhook?utm_source=chatgpt.com) 下载与 Keycloak 版本匹配的 JAR（HTTP provider）。
2. 将 JAR 放置于宿主机 `D:/DockerData/Keycloak/providers`，该目录在 `docker-compose.yml` 中已映射至容器 `/opt/keycloak/providers`。
3. 重启 Keycloak 容器，查看启动日志确认插件加载成功。

### 5.2 docker-compose 中的插件配置

```yaml
environment:
  WEBHOOK_HTTP_BASE_PATH: "http://host.docker.internal:8080/system-service/internal/keycloak/events"
  WEBHOOK_HTTP_EVENTS_INCLUDE: "REGISTER,LOGIN,LOGOUT"
  WEBHOOK_HTTP_AUTH_USERNAME: "admin"
  WEBHOOK_HTTP_AUTH_PASSWORD: "123456"
```

注意事项：
- 值务必使用 **双引号**，否则插件读取不到。
- `host.docker.internal` 需可达（已在 compose 中通过 `extra_hosts` 加入）。若使用固定 IP，请同步修改。
- `WEBHOOK_HTTP_EVENTS_INCLUDE` 控制触发事件类型，也可留空表示全部。

### 5.3 Keycloak 后台配置（参考截图）

1. Realm settings → Events → Event listeners：选择 `webhook-http`。
2. Realm settings → Events → User events settings：勾选需要保存/推送的事件类型（如 REGISTER、LOGIN、LOGOUT）。
3. Realm settings → Login：确保 `User registration` 已打开（否则客户端提交注册不会触发事件）。

### 5.4 回调处理代码

- 入口：`apps/system-service/src/main/java/com/gamehub/systemservice/controller/internal/KeycloakEventController.java`
- 逻辑：
  1. Basic Auth 校验（使用 compose 中配置的用户名/密码）。
  2. 解析 JSON（兼容纯事件格式或封装格式）。
  3. 调用 `KeycloakEventServiceImpl` 处理，例如同步用户。

### 5.5 常见坑整理

1. **忘记启用插件或事件类型** → 后台 Event listeners 未添加 `webhook-http`，或用户事件列表未勾选 REGISTER/LOGIN。
2. **docker-compose 环境变量没加双引号** → 插件读取失败。
3. **URL 路径不完整** → 需要确保 `WEBHOOK_HTTP_BASE_PATH` 指向实际接口（`/events/**`）。
4. **网关与 system-service 各有 SecurityConfig** → 以为冲突导致放弃（历史记录）。目前是正常且必须存在。
5. **容器访问宿主机失败** → 使用 `host.docker.internal` + `extra_hosts`；若仍失败，可改为宿主机 IP。

当以上问题修复后，注册/登录/登出事件即可成功触发并推送到 system-service。

---

## 6. JWT 黑名单策略

### 6.1 为什么要加黑名单？

- JWT 是无状态的，一旦签发就无法在 Keycloak 端强制失效。
- 如果用户主动登出、账号被禁用或密码修改，旧 token 仍可继续访问资源直到过期。
- 引入黑名单可以让 Gateway 在登出后立即拒绝该 token，实现“软撤销”。

### 6.2 当前实现范围

- 目前仅在“用户主动登出”时将 token 加入黑名单。
- 未来可在“禁用用户”、“重置密码”等场景中同样调用黑名单服务，达到即时失效的效果。

### 6.3 处理流程概览

```text
用户点击登出
    ↓
Gateway SecurityConfig.logoutHandler → 读取当前 access token
    ↓
写入黑名单 (JwtBlacklistService: redis set jwt:blacklist:{token})
    ↓
移除 OAuth2AuthorizedClient，防止旧 token 刷新
    ↓
调用 Keycloak OIDC logout → 跳回大厅页面
    ↓
后续任何请求触发 ReactiveJwtDecoder → 先查黑名单 → 命中则抛 JwtException
```

核心代码：
- 黑名单服务 `apps/gateway/.../JwtBlacklistService.java`
- 自定义解码器 `apps/gateway/.../JwtDecoderConfig.java`
- 登出处理器 `apps/gateway/.../SecurityConfig.java#jwtBlacklistLogoutHandler`

### 6.4 不加黑名单的风险

- 用户在多设备登录时，一台设备登出，其他设备仍可继续访问。
- 如果 token 泄露（日志、抓包等），即使用户登出，泄露的 token 也能继续使用直至过期。

---

> 至此，认证链路（Keycloak 配置 → Gateway 集成 → 登录/登出 → 事件驱动 → 黑名单）已经打通。后续扩展（例如禁用用户广播、WS 强制断开）可在此基础上继续迭代。

---

## 7. Keycloak 自定义主题

- 在 `docker-compose.yml` 中已将宿主机目录 `D:/DockerData/Keycloak/themes` 挂载到容器 `/opt/keycloak/themes`。
- 将前端主题资源放入 `themes/gamehub` 目录，例如：

```
themes/
└─ gamehub/
   ├─ login/
   │  ├─ theme.properties
   │  └─ resources/
   │       ├─ css/
   │       │  └─ custom.css
   │       ├─ img/
   │       └─ js/
   └─ account/ ...（按需可选）
```

- 上传/挂载后重启 Keycloak客户端，进入 Realm settings → Themes，将 **Login theme** 选择为 `gamehub` 并保存即可生效。
