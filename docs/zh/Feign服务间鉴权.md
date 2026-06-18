# Feign 服务间鉴权设计

> 本文专门讲清楚 game-hub 各微服务之间通过 OpenFeign 互相调用时，**JWT 是如何被透传的**，以及为什么要这样设计。  
> 涉及代码：`libs/web-common`、`apps/gateway`、`apps/chat-service`、`apps/system-service`、`apps/game-service`。

---

## 1. 设计目标

服务之间互相调用，必须满足两点：

1. **下游能识别"是谁在调用"** —— 不能把所有跨服务调用当成匿名/特权调用，否则审计、限流、权限都会失效。  
2. **不引入第二套凭证体系** —— 不签发 service-to-service 专用 token，复用浏览器登录后 Keycloak 颁发的同一份用户 JWT。

结论：**跨服务调用统一以"原始登录用户"的身份执行**，把同一个 `Authorization: Bearer <jwt>` 从入口请求一路透传到链路最末端。

---

## 2. 总体链路（Web 请求路径）

```
  浏览器
   │  Cookie(SESSION)，或 Authorization: Bearer <jwt>
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ gateway (Spring Cloud Gateway / 响应式)                          │
│  - OAuth2 Client (Authorization Code)：登录后持有 access_token    │
│  - default-filters: TokenRelay  ◄── 把当前用户的 JWT 自动作为     │
│                                      Authorization 头转发下游    │
│  - oauth2ResourceServer.jwt：同时也直接校验 Bearer 请求           │
│  - JwtDecoderConfig：黑名单 + 签名 + 会话状态三层校验             │
└─────────────────────────────────────────────────────────────────┘
   │ Authorization: Bearer <jwt>
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ 下游微服务 (chat / game / system，Servlet 栈)                    │
│  - SecurityConfig: oauth2ResourceServer.jwt(Customizer.withDefaults())  │
│  - 验签通过 → SecurityContext 注入 JwtAuthenticationToken          │
│                                                                  │
│  Controller 调用 Feign Client                                    │
│   │                                                              │
│   ▼                                                              │
│  FeignAuthAutoConfiguration 的 RequestInterceptor                │
│   ├─① 从当前 HTTP 请求头取 Authorization                          │
│   ├─② ThreadLocal (JwtTokenHolder) 取（WebSocket 用）             │
│   └─③ SecurityContext 的 JwtAuthenticationToken 取（兜底）        │
│   → template.header("Authorization", "Bearer ...")               │
└─────────────────────────────────────────────────────────────────┘
   │ Authorization: Bearer <jwt>（同一个 token）
   ▼
  下一个微服务（重复上面的验签 + 透传过程）
```

要点：**JWT 不会被改写、不会重新签发，从浏览器到链路末端始终是同一个字符串**。

---

## 3. 核心组件

| 类 | 模块 | 作用 |
|---|---|---|
| `FeignAuthAutoConfiguration` | `libs/web-common` | 自动注册 Feign `RequestInterceptor`，按三级策略取出 JWT 加到下游请求头 |
| `JwtTokenHolder` | `libs/web-common` | `ThreadLocal<String>`，给 WebSocket 消息线程暂存原始 token |
| `CurrentUserHelper` / `CurrentUserInfo` | `libs/web-common` | 从 JWT 抽取 userId / 角色 / Keycloak claims，统一 API |
| `WebSocketAuthChannelInterceptor` | `apps/chat-service`、`apps/game-service` | STOMP `CONNECT` 阶段解码 JWT，把原始 token 灌进 `JwtTokenHolder` |
| `WebSocketTokenFilter` | `apps/gateway` | SockJS 握手时不能带自定义 header，从 `?access_token=` URL 参数挪到 `Authorization` 头 |
| `JwtDecoderConfig` | `apps/gateway` | 自定义 `ReactiveJwtDecoder`：黑名单 + 签名 + 会话状态 |
| 各服务 `SecurityConfig` | `apps/*` | 标准的 `oauth2ResourceServer.jwt()`，校验同一个 JWT |

---

## 4. 自动装配：怎么"什么都不写"就自动生效

`libs/web-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```
com.gamehub.web.common.feign.FeignAuthAutoConfiguration
```

`FeignAuthAutoConfiguration`：

```java
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
public class FeignAuthAutoConfiguration { ... }
```

**前置条件**：

1. 服务的 `pom.xml` 引入 `web-common`；
2. 引入 `spring-cloud-starter-openfeign`；
3. 启动类加 `@EnableFeignClients`（chat / game / system 都加了）。

满足后 Spring Boot 自动装配，**业务方完全无感知**，所有 `@FeignClient` 调用都会自动带 token。

---

## 5. 拦截器源码精读（三级降级）

`libs/web-common/src/main/java/com/gamehub/web/common/feign/FeignAuthAutoConfiguration.java`：

```java
@Bean
public RequestInterceptor feignRequestInterceptor() {
    return template -> {
        String authorization = null;

        // ① HTTP 请求线程：从当前请求头透传
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            authorization = attrs.getRequest().getHeader("Authorization");
        }

        // ② WebSocket 消息线程：从 ThreadLocal 拿（HTTP 请求线程不会进这里，因为 ① 已经命中）
        if (isBlank(authorization)) {
            String token = JwtTokenHolder.getToken();
            if (token != null && !token.isBlank()) {
                authorization = "Bearer " + token;
            }
        }

        // ③ 兜底：从 SecurityContext 取（异步/线程切换场景）
        if (isBlank(authorization)) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                String v = jwtAuth.getToken().getTokenValue();
                if (v != null && !v.isBlank()) authorization = "Bearer " + v;
            }
        }

        if (!isBlank(authorization)) {
            template.header("Authorization", authorization);
        } else {
            log.error("无法获取 JWT Token，Feign 调用将不携带 Token（会导致 401 错误）, url={}", template.url());
        }
    };
}
```

### 为什么是这个顺序？

- **① 优先级最高**：透传原始头是最忠实的做法，连 token 字符串都和上游一字不差，便于审计。
- **② 仅 WebSocket 路径会用到**：STOMP 消息线程没有 `RequestContextHolder`（不是 HTTP servlet 线程），① 必为 null。
- **③ 兜底**：Spring Security 解码后 `Jwt#getTokenValue()` 仍能拿到原始字符串。这层主要应对**异步任务**或**重新派发到其他线程池**的场景（理论上不该经常触发）。

### 取不到 token 会怎样？

- 不抛异常，**调用照样发出去，但下游 100% 401**。
- 拦截器只打 `log.error`。所以排查"跨服务 401"时第一件事是看日志里有没有这条 `error`。

---

## 6. HTTP 请求场景（最常见）

举例：浏览器调 `chat-service` 的 `GET /chat-service/api/chat/sessions`，业务层要查发送方的禁言状态，于是调 `system-service`。

1. 浏览器请求经过网关，`TokenRelay` 把 access_token 加到 `Authorization` 头。
2. `chat-service` 的 `SecurityConfig`：
   ```java
   http.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
   ```
   `JwtAuthenticationFilter` 校验签名通过，`SecurityContext` 注入 `JwtAuthenticationToken`。
3. Controller 调 `SystemUserClient`（Feign，`@FeignClient(name = "system-service")`）。
4. Feign 拦截器走 **路径 ①**：直接从 `HttpServletRequest.getHeader("Authorization")` 拿到完整 `Bearer <jwt>`，原样塞进下游请求。
5. `system-service` 的 `SecurityConfig` 同样验签，看到相同的 `subject`、`sid`，把请求当作"该用户本人发起"处理。

整条链路**无需手写一行鉴权代码**。

---

## 7. WebSocket / STOMP 场景（关键）

### 7.1 问题：WS 消息线程没有 HTTP 上下文

STOMP `@MessageMapping` 方法在 message broker 的线程池上执行，**不是 servlet 线程**，所以：

- `RequestContextHolder.getRequestAttributes()` → `null` ⇒ Feign 拦截器 ① 失效。
- `SecurityContext` 在 STOMP 默认实现下也不会自动绑定。

如果不管，业务一调 Feign 就 401。

### 7.2 解决方案：ThreadLocal + Redis 双备份

`apps/chat-service/.../config/WebSocketAuthChannelInterceptor.java`（game-service 同构）：

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        // STOMP header / HTTP 握手头 / URL 参数 三选一拿到 Bearer
        String token = extractBearer(accessor);
        Jwt jwt = jwtDecoder.decode(token);                 // 验签
        JwtAuthenticationToken authentication = build(jwt);  // 构造认证对象
        accessor.setUser(authentication);

        // (a) 给 Feign 用：原始字符串塞 ThreadLocal
        JwtTokenHolder.setToken(token);

        // (b) 给后续 SEND 消息用：按 loginSessionId(=jwt.sid) 和 wsSessionId 双 key 存 Redis
        String loginSessionId = jwt.getClaimAsString("sid");
        tokenStore.putToken(loginSessionId, token);
        tokenStore.putToken(accessor.getSessionId(), token);

        // (c) 还原 SecurityContext，让 ③ 兜底也能命中
        SecurityContextHolder.setContext(new SecurityContextImpl(authentication));

    } else {
        // 非 CONNECT 帧（SEND / SUBSCRIBE）
        // 1) 优先用 jwt.sid 从 Redis 取 token（token 刷新时 sid 不变，能命中新 token）
        // 2) 降级用 wsSessionId
        String token = tokenStore.getToken(loginSessionIdOrWsSessionId);
        if (token != null) JwtTokenHolder.setToken(token);
        SecurityContextHolder.setContext(...);
    }
    return message;
}
```

为什么用 `sid` 而不是 `wsSessionId` 作为 Redis 主 key？  
> **Token 刷新时 wsSessionId 不变，但 JWT 整体变了**。如果只按 wsSessionId 存，刷新后旧 token 还在用；按 `sid` 存就能用新 token 覆盖旧的。

`JwtTokenHolder`：

```java
public class JwtTokenHolder {
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();
    public static void setToken(String token) { TOKEN_HOLDER.set(token); }
    public static String getToken() { return TOKEN_HOLDER.get(); }
    public static void clear() { TOKEN_HOLDER.remove(); }
}
```

⚠ 拦截器**没有主动 `clear()`**，靠每条消息处理时覆盖。原因写在 `afterSendCompletion` 的注释里：清理时机不好把握，Feign 调用可能还在异步进行中。这是一个可接受的权衡，因为：

1. STOMP 消息线程是池化的，下一条消息进来一定会重新 `setToken`；
2. 不会跨用户复用 —— 同一连接的所有消息属于同一用户。

但如果将来引入**异步线程池**做 Feign 调用，要么自己包一层 `TaskDecorator` 透传 ThreadLocal，要么改成在 Feign 拦截器里手动从 `SecurityContext` 取。

### 7.3 SockJS 握手特殊处理

SockJS 的 HTTP 握手请求**不能携带自定义 header**，前端把 token 放在 URL 参数 `?access_token=...`。网关的 `WebSocketTokenFilter`（`GlobalFilter`，order = `HIGHEST_PRECEDENCE + 100`）负责把它挪到 `Authorization` 头：

```java
if (!path.contains("/ws/")) return chain.filter(exchange);

String token = exchange.getRequest().getQueryParams().getFirst("access_token");
if (token != null) {
    ServerWebExchange mutated = exchange.mutate()
        .request(b -> b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .build();
    return chain.filter(mutated);
}
```

挪到头之后，后面的 `TokenRelay` 和下游服务的 `WebSocketAuthChannelInterceptor` 走和 HTTP 完全一致的路径。

---

## 8. 下游服务怎么验签

各业务服务（chat / game / system）的 `SecurityConfig` 是标准 Spring Security 配置，没有自定义解码器：

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable());
    http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/ws/**", "/api/**").authenticated()
        .anyRequest().authenticated());
    http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
}
```

`Customizer.withDefaults()` 会从 `application.yml` 读 `spring.security.oauth2.resourceserver.jwt.issuer-uri`，自动用 Keycloak 的 JWKS 验签。验签通过即视为合法 —— **下游服务不再二次检查黑名单/会话状态**，那是**网关的职责**。

### 网关的额外校验（不在 Feign 链路内，但要知道）

`gateway` 的 `JwtDecoderConfig` 自定义 `ReactiveJwtDecoder`：

1. **黑名单**：Redis 里是否被拉黑（登出、被踢、401 触发等情况会写入，TTL = 剩余有效期）；
2. **签名**：Nimbus 默认实现；
3. **会话状态**：`SessionRegistry` 里登录会话必须为 `ACTIVE`。

意味着：**用户被踢下线 → 网关入口立即 401 → 浏览器拿不到响应**，但**下游服务内部仍只做签名校验**。如果直接绕过网关访问下游（开发模式下可能发生），下游会接受未拉黑前的 token。

> 设计取舍：把会话状态检查集中在网关，避免每个下游都查 Redis、保持下游轻量。生产环境下游应该网络层就只允许网关访问。

---

## 9. 现有 Feign Client 清单

| 调用方 | 接口 | 目标服务 | 路径 |
|---|---|---|---|
| `chat-service` | `SystemUserClient` | `system-service` | `/api/users/{id}/info`、`/api/users/{id}/muted`、`/api/friends/check/{a}/{b}` |
| `chat-service` | `GameRoomClient` | `game-service` | `/api/game/rooms/{roomId}/members` |
| `system-service` | `ChatNotifyClient` | `chat-service` | `POST /api/internal/notify` |
| `game-service` | `SystemUserClient` | `system-service` | `/api/users/users/batch`、`/api/users/users/{id}`、`/api/users/me/full` |

所有 Feign Client 都标了 `fallback`（Resilience4j 的 `@CircuitBreaker`），下游不可用时返回降级结果而不是抛异常 —— 但**降级不绕过鉴权**：Feign 拦截器依然会先尝试加 header，只是请求本身被熔断了。

### `/api/internal/notify` 为什么不豁免鉴权？

`NotificationInternalController` 的注释明确写了：

> 安全：接口需要网关/oauth2 鉴权，未做额外白名单。

`system-service` 调它时，靠 Feign 拦截器把"触发通知的那个用户"的 JWT 透传过来 ⇒ chat-service 能识别"谁让我推这条通知"，可以做审计。这比"内部 IP 白名单 + 跳过鉴权"更安全：即便后端服务被入侵，攻击者也无法以"任意用户"身份发通知（除非已经拿到了那个用户的有效 JWT）。

---

## 10. 加新 Feign Client 的标准步骤

1. **接口定义**（业务方所在服务）：
   ```java
   @FeignClient(
       name  = "system-service",   // Spring Cloud LoadBalancer 用此名查实例
       path  = "/api/xxx",         // 可选，统一路径前缀
       fallback = XxxClientFallback.class)
   public interface XxxClient {
       @GetMapping("/{id}")
       @CircuitBreaker(name = "xxxClient")
       Result get(@PathVariable String id);
   }
   ```
2. **不用**自己写 `RequestInterceptor` 加 token —— web-common 已经全局处理。
3. **不用**写 `Configuration` 类 —— 启动类的 `@EnableFeignClients` 默认扫整个包。
4. 下游 Controller 上加 `@AuthenticationPrincipal Jwt jwt`（或用 `CurrentUserHelper.from(jwt)`）就能拿到调用方用户身份。

---

## 11. 常见问题与排查

| 现象 | 可能原因 | 排查方向 |
|---|---|---|
| 跨服务调用 401，浏览器请求 200 | Feign 拦截器没加 header | 看日志是否有 `无法获取 JWT Token，Feign 调用将不携带 Token` |
| WebSocket 路径下 Feign 401 | ThreadLocal 没被设进去 | 看 `WebSocketAuthChannelInterceptor.preSend` 日志，确认 CONNECT 时 `JwtTokenHolder.setToken` 调用过 |
| Token 刷新后 WS 还在用旧 token | Redis 里按 `wsSessionId` 存的没被覆盖 | 确认 `sid` 提取逻辑生效（`jwt.getClaim("sid")` 非空） |
| 直连下游服务可用，经网关 401 | token 被拉黑 / 会话状态非 ACTIVE | 查 Redis 黑名单 key、`SessionRegistry` 的状态 |
| 通过网关的请求带不上 token | `default-filters: TokenRelay` 没配 | 检查 `gateway/application.yml` 是否有 `TokenRelay` 全局过滤器 |
| 静态资源也要求登录 | 未在网关或下游放行 | 网关的 `pathMatchers(...).permitAll()`，下游的 `requestMatchers(...).permitAll()` |

---

## 12. 一句话总结

**`web-common` 的 `FeignAuthAutoConfiguration` 自动注册一个 Feign `RequestInterceptor`，按"HTTP 请求头 → ThreadLocal（WebSocket）→ SecurityContext（兜底）"的顺序取出 Keycloak 颁发的 JWT，统一作为 `Bearer` 透传给下游服务。** 下游用 `oauth2-resource-server` 验签同一份 JWT，整条调用链以"原始登录用户"身份执行，**业务代码零侵入**。
