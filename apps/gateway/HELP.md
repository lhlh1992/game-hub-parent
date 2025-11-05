# 🌀 Gateway 模块说明

> 模块路径：`game-platform/gateway`  
> 技术栈：**Spring Cloud Gateway (WebFlux + Netty)**  
> 主要作用：**系统统一入口与网关层**

---

## 📖 模块职责

- **统一入口**：所有前端、客户端请求都先经过 Gateway，再路由到后端微服务。
- **路由与负载均衡**：根据路径 `/auth/**`、`/game/**` 等匹配规则，将请求转发到不同服务。
- **统一跨域 (CORS)**：集中处理跨域逻辑，前端只配置网关域名。
- **统一鉴权过滤**：
    - 校验 JWT Token；
    - 游客模式放行；
    - 拦截未授权请求；
    - 统一响应错误格式。
- **限流与防刷**：通过 `RequestRateLimiter` 实现接口限流。
- **日志与 Trace**：
    - 打印访问日志；
    - 注入 TraceId / RequestId；
    - 统一输出请求耗时、状态码。
- **熔断与重试**：下游服务异常时快速响应或自动重试。
- **WebSocket 透传支持**：可转发前端 WebSocket 请求至 `game-service`。
- **监控与健康检查**：提供 `/actuator/**` 指标接口，便于 Prometheus / Grafana 监控。

---

## ⚙️ 主要依赖

| 依赖 | 作用 |
|------|------|
| `spring-cloud-starter-gateway` | 网关核心，基于 WebFlux + Netty |
| `spring-cloud-starter-loadbalancer` | 服务间调用的客户端负载均衡 |
| `spring-boot-starter-actuator` | 健康检查与监控指标 |
| `spring-boot-starter-test` | 测试基础依赖 |

---

## 🔐 Gateway 与 Keycloak 对接（简明说明）

- 角色
  - Gateway：OAuth2 客户端（Client）+ 资源服务器（Resource Server）
  - Keycloak：授权服务器（Authorization Server / OpenID Provider）

- 关键配置（application.yml）
  - `spring.security.oauth2.client.registration.keycloak`：客户端注册（client-id、client-secret、scope、redirect-uri 模板）
  - `spring.security.oauth2.client.provider.keycloak`：授权服务器（通过 `issuer-uri` 自动发现授权端点/令牌端点/JWKS）
  - `spring.security.oauth2.resourceserver.jwt.issuer-uri`：资源服务器校验 JWT 的签发者

- 重要端点（由 Spring Security 提供，非页面）
  - 发起登录：`/oauth2/authorization/{registrationId}` 例：`/oauth2/authorization/keycloak`
  - 回调处理：`/login/oauth2/code/{registrationId}` 例：`/login/oauth2/code/keycloak`
    - 该回调 URL 必须出现在 Keycloak 客户端的 Valid redirect URIs 中（如：`http://localhost:8080/login/oauth2/code/keycloak`）

- 登录流程（授权码模式，浏览器可见/不可见）
  1) 未登录访问受保护资源 → Gateway 根据安全规则触发重定向到 `/oauth2/authorization/keycloak`
  2) Gateway 根据 `issuer-uri` 自动发现 Keycloak 的 `authorization_endpoint`，构造授权 URL 并重定向到 Keycloak 登录页
  3) 用户在 Keycloak 登录成功 → Keycloak 按回调地址重定向到 `/login/oauth2/code/keycloak?code=...&state=...`
  4) Gateway（服务端）用授权码向 Keycloak `token_endpoint` 换取 `access_token`，存入会话（Session/ReactiveAuthorizedClientService）
  5) 登录完成，按保存的原始地址跳回；前端若需要原始 JWT，可调用网关提供的 `/token` 获取

- 前后端如何拿到用户 Token
  - Gateway 对下游转发时使用全局过滤器 `TokenRelay` 透传请求头中的 `Authorization: Bearer <token>`
  - 前端通过调用网关自带的 `/token`（见 `TokenController`）获取当前登录用户的 `access_token` 用于直接请求下游或建 WS 连接

- 可自定义项
  - `registrationId`（如 `keycloak`）可改名，但需同步修改：前端登录入口 `/oauth2/authorization/{registrationId}`、回调白名单、`TokenController` 中使用到的注册名
  - 回调基址可改（例如 `/login/oauth2/code1/*`），需同时：修改 `redirect-uri` 模板、Spring Security 的回调基址、Keycloak 的 Valid redirect URIs

提示：上述端点均为 Spring Security 内置处理器，不需要额外页面。

### 这两个 URL 从哪里来？

- `/oauth2/authorization/keycloak`
  - 来源：Spring Security OAuth2 Client 的“登录发起端点”默认基址 `/oauth2/authorization` + 你的 `registrationId`（这里是 `keycloak`）。
  - 作用：不是页面，而是框架内置处理器。它读取 `registration.keycloak` 与 `provider.keycloak`，拼出授权服务器的授权 URL，然后重定向过去。

- `http://127.0.0.1:8180/realms/my-realm/protocol/openid-connect/auth`
  - 来源：根据 `issuer-uri` 自动发现（`.well-known/openid-configuration`）里返回的 `authorization_endpoint`。
  - 作用：Keycloak 的授权端点（显示登录页），由 Keycloak 决定路径规则。

两者关系：前者是“网关本地的登录入口”（框架内置），后者是“Keycloak 远端的授权端点”。访问前者后会被重定向到后者。

### 对接示意图

```
┌─────────────────┐                          ┌──────────────────┐
│     Gateway     │                          │     Keycloak     │
│  (OAuth2 Client)│                          │ (Authorization   │
│                 │                          │     Server)      │
└────────┬────────┘                          └─────────┬────────┘
         │                                           │
         │ 1) 用户请求受保护资源                     │
         │                                           │
         │ 2) 触发登录 → 重定向到                    │
         │    /oauth2/authorization/keycloak         │
         │──────────────────────────────────────────▶│
         │                                           │
         │ 3) Gateway 依据 issuer-uri 自动发现       │
         │    authorization_endpoint 并重定向至      │
         │    https://.../openid-connect/auth        │
         │──────────────────────────────────────────▶│
         │                                           │
         │ 4) 用户在 Keycloak 登录成功               │
         │ ◀─────────────────────────────────────────│
         │    回调 /login/oauth2/code/keycloak?code= │
         │                                           │
         │ 5) Gateway（服务端）用 code 换 token      │
         │    POST token_endpoint                     │
         │──────────────────────────────────────────▶│
         │                                           │
         │ 6) 保存 access_token 到会话                │
         │    （ReactiveAuthorizedClientService）     │
         │                                           │
         │ 7) 重定向回原始地址，后续通过             │
         │    TokenRelay 透传或 /token 提供给前端     │
         ▼                                           ▼
```


