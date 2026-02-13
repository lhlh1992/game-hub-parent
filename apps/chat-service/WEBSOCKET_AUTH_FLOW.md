# WebSocket 鉴权流程详解

> 详细说明 chat-service 的 WebSocket 鉴权机制

---

## 🔐 鉴权流程概览

```
前端
  ↓
  [方式1] URL 查询参数: /ws?access_token=xxx
  [方式2] STOMP Header: Authorization: Bearer xxx
  ↓
Gateway (WebSocketTokenFilter)
  ↓
  从 URL 查询参数提取 access_token
  ↓
  添加到 HTTP 请求头: Authorization: Bearer xxx
  ↓
chat-service (WebSocketAuthChannelInterceptor)
  ↓
  [方式1] 从 STOMP header 获取
  [方式2] 从 HTTP 请求头获取（Gateway 放入的）
  ↓
  解析 JWT Token
  ↓
  设置用户身份 (JwtAuthenticationToken)
```

---

## 📋 详细步骤

### 1. 前端传递 Token

前端使用**双重传递**策略，确保 Token 能够到达服务端：

#### 方式1：URL 查询参数（用于 SockJS 握手）

```javascript
// chatSocket.js
const token = await ensureAuthenticated()
const wsUrl = `/chat-service/ws?access_token=${encodeURIComponent(token)}`
socket = new SockJS(wsUrl)
```

**原因**：SockJS 的 HTTP 握手请求无法在请求头中传递自定义 header，但可以通过 URL 参数传递。

#### 方式2：STOMP CONNECT 帧 Header（用于 STOMP 连接）

```javascript
// chatSocket.js
const headers = { Authorization: 'Bearer ' + token }
stomp.connect(headers, (frame) => {
  // 连接成功
})
```

**原因**：STOMP 协议支持在 CONNECT 帧的 header 中传递认证信息。

---

### 2. Gateway 处理（WebSocketTokenFilter）

**文件位置**：`apps/gateway/src/main/java/com/gamehub/gateway/filter/WebSocketTokenFilter.java`

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    // 仅处理 WebSocket 相关的请求
    String path = exchange.getRequest().getURI().getPath();
    if (!path.contains("/ws/")) {
        return chain.filter(exchange);
    }

    // 如果请求头中已存在 Authorization，直接放行
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader != null && !authHeader.isBlank()) {
        return chain.filter(exchange);
    }

    // 从 URL 查询参数中提取 access_token
    String token = exchange.getRequest().getQueryParams().getFirst("access_token");
    if (token != null && !token.isBlank()) {
        // 将 token 添加到 HTTP 请求头中
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .build();
        return chain.filter(mutated);
    }

    return chain.filter(exchange);
}
```

**关键点**：
- 从 URL 查询参数（`?access_token=xxx`）中提取 token
- 将 token 添加到 HTTP 请求头：`Authorization: Bearer {token}`
- 这样服务端可以通过 HTTP 请求头获取 token（即使 STOMP header 中没有）

---

### 3. 服务端处理（WebSocketAuthChannelInterceptor）

**文件位置**：`apps/chat-service/src/main/java/com/gamehub/chatservice/config/WebSocketAuthChannelInterceptor.java`

#### 3.1 CONNECT 阶段（建立连接时）

```java
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
        // 方式1：从 STOMP header 中获取（客户端在 CONNECT 时传递）
        String auth = firstHeader(accessor, "Authorization");
        if (auth == null) auth = firstHeader(accessor, "authorization");
        if (auth == null) {
            String tokenOnly = firstHeader(accessor, "access_token");
            if (tokenOnly != null && !tokenOnly.isBlank()) {
                auth = "Bearer " + tokenOnly.trim();
            }
        }
        
        // 方式2：如果 STOMP header 中没有，尝试从 HTTP 请求头中获取（gateway 过滤器放入的）
        if ((auth == null || auth.isBlank())) {
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    if (request != null) {
                        auth = request.getHeader("Authorization");
                        if (auth == null || auth.isBlank()) {
                            auth = request.getHeader("authorization");
                        }
                        // 从 HTTP 请求头获取到 token（gateway 过滤器放入的）
                    }
                }
            } catch (Exception e) {
                // 无法从 HTTP 请求上下文获取 token
            }
        }

        // 解析 JWT Token
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            String token = auth.substring(7).trim();
            Jwt jwt = jwtDecoder.decode(token);
            // ... 设置用户身份
            accessor.setUser(authentication);
        }
    }
    return message;
}
```

**关键点**：
- **优先级1**：从 STOMP header 中获取（`Authorization` 或 `access_token`）
- **优先级2**：从 HTTP 请求头中获取（Gateway 过滤器放入的）
- 解析 JWT Token 并设置用户身份

#### 3.2 非 CONNECT 阶段（发送消息时）

对于后续的 SEND 消息，拦截器会：
1. 从已建立的会话中获取用户身份
2. 从 `WebSocketTokenStore` 中获取 token（用于 Feign 调用）
3. 设置到 `SecurityContext` 和 `JwtTokenHolder`

---

## 🔍 为什么需要双重传递？

### 问题背景

1. **SockJS 握手限制**：SockJS 的 HTTP 握手请求无法在请求头中传递自定义 header
2. **STOMP 协议支持**：STOMP 协议支持在 CONNECT 帧的 header 中传递认证信息

### 解决方案

**双重传递策略**：
- **URL 参数**：确保 Gateway 能够提取 token 并放入 HTTP 请求头
- **STOMP Header**：确保服务端能够直接从 STOMP 协议中获取 token

**降级策略**：
- 如果 STOMP header 中没有 token，服务端会尝试从 HTTP 请求头获取（Gateway 放入的）
- 这样即使前端只传递了 URL 参数，服务端也能正常工作

---

## 📊 完整流程图

```
┌─────────┐
│  前端   │
└────┬────┘
     │
     ├─→ URL 参数: /ws?access_token=xxx
     └─→ STOMP Header: Authorization: Bearer xxx
     │
     ↓
┌─────────────────┐
│ Gateway         │
│ WebSocketTokenFilter │
└────┬────────────┘
     │
     ├─→ 从 URL 查询参数提取 access_token
     └─→ 添加到 HTTP 请求头: Authorization: Bearer xxx
     │
     ↓
┌─────────────────┐
│ chat-service    │
│ WebSocketAuthChannelInterceptor │
└────┬────────────┘
     │
     ├─→ [方式1] 从 STOMP header 获取
     │   └─→ Authorization 或 access_token
     │
     ├─→ [方式2] 从 HTTP 请求头获取（降级）
     │   └─→ Gateway 过滤器放入的
     │
     └─→ 解析 JWT Token
         └─→ 设置用户身份 (JwtAuthenticationToken)
```

---

## ✅ 你的理解 vs 实际实现

### 你的描述

> 前端的ws请求带着token，gateway服务有个ws过滤器，过滤器会解析ws的url中的token，放入请求头中。每个服务有ws的拦截器，拦截器解析请求头中的JWT。

### 实际实现

你的理解**基本正确**，但有一个小细节：

| 你的描述 | 实际实现 | 说明 |
|---------|---------|------|
| "解析ws的url中的token" | ✅ 从 URL **查询参数**中提取 | 不是从 URL 路径，而是从 `?access_token=xxx` |
| "放入请求头中" | ✅ 添加到 HTTP 请求头 `Authorization: Bearer xxx` | 正确 |
| "拦截器解析请求头中的JWT" | ✅ 从 STOMP header 或 HTTP 请求头获取 | 双重获取策略 |

### 补充说明

1. **前端双重传递**：
   - URL 查询参数：`/ws?access_token=xxx`（用于 SockJS 握手）
   - STOMP Header：`Authorization: Bearer xxx`（用于 STOMP 连接）

2. **服务端双重获取**：
   - 优先从 STOMP header 获取
   - 降级从 HTTP 请求头获取（Gateway 放入的）

3. **为什么需要 Gateway 过滤器**：
   - SockJS 握手请求无法在请求头中传递自定义 header
   - Gateway 过滤器将 URL 参数中的 token 提取并放入 HTTP 请求头
   - 这样服务端可以通过 HTTP 请求头获取 token（即使 STOMP header 中没有）

---

## 🎯 总结

你的理解**非常准确**！只需要注意：

1. ✅ Gateway 从 URL **查询参数**（`?access_token=xxx`）中提取 token
2. ✅ Gateway 将 token 添加到 HTTP 请求头 `Authorization: Bearer xxx`
3. ✅ 服务端拦截器从 STOMP header 或 HTTP 请求头中获取 token
4. ✅ 服务端解析 JWT Token 并设置用户身份

**关键点**：前端使用双重传递策略，服务端使用双重获取策略，确保鉴权的可靠性。

---

**文档版本**：v1.0  
**最后更新**：2025-01-XX




















