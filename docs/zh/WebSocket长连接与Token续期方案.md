# WebSocket 长连接中的 JWT 续期问题与解决方案

## 1. 当前问题描述

### 1.1 场景

在我们的系统中，用户通过 WebSocket 与 `chat-service` 建立长连接以实现实时通信。当用户发送私聊消息时，`chat-service` 需要通过 Feign 调用 `system-service` 的 `isFriend` 接口，来验证发送方和接收方是否为好友关系。这个跨服务调用需要提供有效的用户身份凭证（JWT）。

### 1.2 问题根源

1.  **WebSocket 连接的特性**：WebSocket 连接只在初次握手（STOMP 的 CONNECT 阶段）时验证一次 JWT (Access Token)。一旦连接成功建立，这个底层的 TCP 连接会一直保持，它本身并不会因为上层 Token 的过期而自动断开。

2.  **JWT 的生命周期**：Access Token 的有效期通常很短（例如 15 分钟），这是为了安全考虑。

3.  **矛盾爆发**：当一个 WebSocket 连接的存活时间超过了 Access Token 的有效期（比如连接了 20 分钟），此时用户发送了一条私聊消息。`chat-service` 在处理这个消息时，会尝试使用当初连接时那个**已经过期的 Token** 去调用 `system-service`。

### 1.3 最终结果

`system-service` 收到来自 `chat-service` 的 Feign 请求后，会独立验证请求头中的 JWT。当它发现 Token 已过期时，会拒绝该请求并返回 **401 Unauthorized** 错误。这最终导致用户的私聊功能在长连接建立一段时间后失灵。

-   **代码落点**：`game-hub-parent/apps/chat-service/src/main/java/com/gamehub/chatservice/service/impl/ChatMessagingServiceImpl.java` 中的 `sendPrivateMessage` 方法是这个问题的直接触发点。

## 2. 问题定性

这个问题在业界非常普遍，并非本项目的特有设计缺陷，而是所有采用“长连接 + 微服务鉴权”架构都会面临的经典挑战。它的专业术语包括：

-   **长连接的令牌管理 (Token Management for Long-Lived Connections)**
-   **WebSocket 会话中的无感令牌刷新 (Silent Token Refresh in WebSocket Sessions)**

其核心矛盾在于：如何在一个**有状态的长连接 (Stateful Long-Lived Connection)** 中，优雅地处理**无状态、短时效凭证 (Stateless Short-Lived Credential)** 的生命周期问题。

## 3. 详细解决方案：后端静默刷新 (Backend Silent Token Refresh)

这是业界最主流、最专业的解决方案，核心思想是将 Token 续期逻辑完全封装在后端服务内部（本例中为 `chat-service`），对前端用户和被调用的下游服务（`system-service`）完全透明，且不中断用户的 WebSocket 连接。

### 步骤一：安全地存储 Refresh Token

**目标**：在 WebSocket 连接建立时，将用于“续命”的 Refresh Token 保存下来。

1.  **修改登录流程**：在用户通过 Gateway 登录成功后，不仅要返回 Access Token，还必须将 **Refresh Token** 也安全地传递给后续流程。
2.  **修改 WebSocket 连接逻辑**：当 WebSocket 第一次连接成功时，后端（`chat-service`）在验证完初始的 Access Token 后，需要将与之配对的 **Refresh Token** 也保存起来。
3.  **选择存储位置**：**Redis** 是最佳选择，因为它支持跨实例共享和设置过期时间。
    -   **Key**: `ws:session:auth:{sessionId}` (sessionId 是 WebSocket 连接的唯一ID)
    -   **Value**: 一个 JSON 对象，至少包含 `{"accessToken": "...", "refreshToken": "..."}`
    -   **TTL**: 这个 Redis Key 的过期时间应与 Refresh Token 的过期时间保持一致（例如几天或几周）。

### 步骤二：创建 Feign 请求拦截器

**目标**：在 `chat-service` 的每一次 Feign 调用发出前，都设置一个“安检口”来检查和处理 Token。

1.  **创建拦截器**：在 `chat-service` 中创建一个实现了 `feign.RequestInterceptor` 接口的 Spring Bean。
2.  **注册拦截器**：将其配置为 `SystemUserClient` 这个 Feign 客户端的拦截器。

### 步骤三：实现智能的刷新逻辑

**目标**：在拦截器中实现“检查-刷新-放行”的核心逻辑。

在 `RequestInterceptor` 的 `apply` 方法中，执行以下操作：

1.  **获取当前 Token**：从 Redis 中根据当前操作关联的 WebSocket `sessionId`，取出之前存的 `accessToken`。

2.  **检查是否过期**：在本地快速解析这个 `accessToken`，判断它是否已经过期（或即将在短时间内，如 30 秒内，过期）。

3.  **分支处理**：
    -   **如果 Token 有效**：直接将此 Token 设置到 Feign 请求的 `Authorization` 头中，然后放行请求。
    -   **如果 Token 已过期**：触发**静默刷新流程**：
        a.  **获取分布式锁**：为了防止多个并发请求为同一个会话重复刷新 Token，需要先获取一个基于 `sessionId` 的分布式锁（使用 Redis 的 `SETNX` 即可）。
        b.  **抢到锁的线程执行刷新**：
            i.  从 Redis 中拿出 `refreshToken`。
            ii. 使用 `RestTemplate` 或 `WebClient` 向 Keycloak 的 `/token` 端点发起一次 `grant_type=refresh_token` 的 HTTP POST 请求。
            iii. 接收 Keycloak 返回的**新的 Access Token 和新的 Refresh Token**。
            iv.  用这对新 Token **覆盖 Redis 中的旧记录**。
            v.  释放分布式锁。
            vi. 使用这个**崭新的 Access Token** 来完成本次 Feign 调用。
        c.  **未抢到锁的线程**：
            i.  不自己去刷新，而是原地等待一小段时间（例如 100 毫秒）。
            ii. 重新去 Redis 中获取 Token（此时大概率已被抢到锁的线程刷新好了）。
            iii. 使用获取到的新 Token 完成 Feign 调用。

### 步骤四：处理最终失败：Refresh Token 也过期了

-   如果在静默刷新时，连 Refresh Token 本身都过期了，Keycloak 会返回错误。
-   此时，拦截器应捕获这个错误，并认为该用户的整个登录会话已彻底失效。
-   `chat-service` 应该立即**从服务器端主动断开这个 WebSocket 连接**，并记录一条明确的错误日志。前端的自动重连机制会因为没有有效身份而失败，最终引导用户去重新登录。

通过以上四个步骤，即可在不中断用户连接、不打扰用户体验的前提下，完美解决 WebSocket 长连接中的 JWT 续期问题。
