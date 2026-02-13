# WebSocket 鉴权设计原理解析

> 深入解释为什么需要双重传递 token，以及 Gateway 和服务端的分工设计

---

## ❓ 问题1：为什么前端既要从查询参数传 token，又要在 STOMP Header 中传？

### 关键理解：WebSocket 连接有两个阶段

```
阶段1：SockJS HTTP 握手（建立 WebSocket 连接）
  ↓
阶段2：STOMP CONNECT 帧（建立 STOMP 协议连接）
```

### 阶段1：SockJS HTTP 握手

**问题**：SockJS 的 HTTP 握手请求**无法在请求头中传递自定义 header**

```javascript
// ❌ 这样不行：SockJS 握手请求无法设置自定义 header
const socket = new SockJS('/chat-service/ws', null, {
  headers: { Authorization: 'Bearer xxx' }  // 浏览器会忽略这个
})

// ✅ 只能通过 URL 查询参数传递
const wsUrl = `/chat-service/ws?access_token=${token}`
const socket = new SockJS(wsUrl)
```

**原因**：
- SockJS 使用标准的 HTTP 请求进行握手
- 浏览器的 `XMLHttpRequest` 和 `fetch` API 对自定义 header 有 CORS 限制
- 但 URL 查询参数不受限制

### 阶段2：STOMP CONNECT 帧

**优势**：STOMP 协议支持在 CONNECT 帧的 header 中传递认证信息

```javascript
// ✅ STOMP 协议层面支持 header
const headers = { Authorization: 'Bearer ' + token }
stomp.connect(headers, (frame) => {
  // 连接成功
})
```

**原因**：
- STOMP 是应用层协议，运行在 WebSocket 之上
- STOMP CONNECT 帧有自己的 header 机制
- 不受浏览器 HTTP 请求限制

### 为什么需要双重传递？

| 传递方式 | 作用阶段 | 原因 |
|---------|---------|------|
| **URL 查询参数** | SockJS HTTP 握手 | 浏览器限制，无法在 HTTP 请求头中传递自定义 header |
| **STOMP Header** | STOMP CONNECT 帧 | STOMP 协议支持，更直接、更标准 |

**降级策略**：
- 如果 STOMP header 中有 token → 优先使用（更直接）
- 如果 STOMP header 中没有 → 从 HTTP 请求头获取（Gateway 从 URL 参数提取的）

---

## ❓ 问题2：为什么要设计成 Gateway 过滤器提取 + 服务端拦截器验证？

### 设计架构

```
前端
  ↓
Gateway (WebSocketTokenFilter)
  ├─→ 从 URL 查询参数提取 token
  └─→ 放入 HTTP 请求头: Authorization: Bearer xxx
  ↓
服务端 (WebSocketAuthChannelInterceptor)
  ├─→ [优先] 从 STOMP header 获取 ✅
  ├─→ [降级] 从 HTTP 请求头获取（Gateway 放入的）
  └─→ 解析 JWT Token 并设置用户身份
```

### ⚠️ 关键理解：Gateway 这一步主要是降级策略

**你的理解是正确的！**

从代码逻辑来看：
1. **服务端拦截器优先从 STOMP header 获取 token**（如果前端传递了）
2. **如果 STOMP header 中没有，才从 HTTP 请求头获取**（Gateway 放入的）
3. **Gateway 过滤器主要是作为降级策略**，确保即使前端只传递了 URL 参数，服务端也能获取 token

### 为什么这样设计？

#### 1. 降级策略（Fallback Strategy）- **主要目的**

**服务端拦截器的双重获取策略**：

```java
// 优先级1：从 STOMP header 获取（更直接，如果前端传递了）
String auth = firstHeader(accessor, "Authorization");

// 优先级2：从 HTTP 请求头获取（Gateway 放入的，降级方案）
if (auth == null) {
    auth = request.getHeader("Authorization");
}
```

**为什么需要降级？**
- 如果前端只传递了 URL 参数（没有 STOMP header）
- Gateway 过滤器会提取并放入 HTTP 请求头
- 服务端可以从 HTTP 请求头获取（降级方案）

**好处**：
- 即使前端实现不完整，服务端也能正常工作
- 提高系统的容错性

#### 2. 技术限制的解决方案

**问题**：SockJS 握手请求无法在请求头中传递自定义 header

**解决方案**：
1. Gateway 过滤器：从 URL 查询参数提取 token → 放入 HTTP 请求头
2. 服务端拦截器：从 HTTP 请求头获取 token（即使 STOMP header 中没有）

**好处**：
- 绕过浏览器的 HTTP 请求限制
- 服务端可以统一从 HTTP 请求头获取 token

#### 3. 统一处理（Centralized Processing）- **次要目的**

**Gateway 统一处理所有 WebSocket 请求**：
- 所有服务的 WebSocket 请求都经过 Gateway
- Gateway 统一从 URL 查询参数提取 token
- 统一放入 HTTP 请求头，供所有服务使用

**好处**：
- 避免每个服务重复实现 token 提取逻辑
- 统一处理，便于维护和升级

#### 4. 职责分离（Separation of Concerns）- **设计原则**

| 组件 | 职责 | 原因 |
|------|------|------|
| **Gateway 过滤器** | Token 提取和转换 | 统一处理所有 WebSocket 请求的 token 提取 |
| **服务端拦截器** | Token 验证和身份设置 | 每个服务自己验证 token，设置用户身份 |

**好处**：
- Gateway 只负责"提取和传递"，不关心 token 的内容
- 服务端只负责"验证和使用"，不关心 token 从哪里来

---

## 📊 完整流程解析

### 场景1：前端完整实现（双重传递）

```
前端
  ├─→ URL 参数: /ws?access_token=xxx
  └─→ STOMP Header: Authorization: Bearer xxx
  ↓
Gateway
  ├─→ 从 URL 参数提取 token
  └─→ 放入 HTTP 请求头: Authorization: Bearer xxx
  ↓
服务端
  ├─→ [优先] 从 STOMP header 获取 ✅
  └─→ [降级] 从 HTTP 请求头获取（备用）
```

### 场景2：前端只传递 URL 参数

```
前端
  └─→ URL 参数: /ws?access_token=xxx
  ↓
Gateway
  ├─→ 从 URL 参数提取 token
  └─→ 放入 HTTP 请求头: Authorization: Bearer xxx
  ↓
服务端
  ├─→ [优先] 从 STOMP header 获取 ❌（没有）
  └─→ [降级] 从 HTTP 请求头获取 ✅（Gateway 放入的）
```

---

## 🎯 设计原则总结

### 1. 单一职责原则（SRP）

- **Gateway**：只负责 token 提取和传递
- **服务端**：只负责 token 验证和身份设置

### 2. 开闭原则（OCP）

- Gateway 的过滤器是通用的，不依赖具体服务
- 服务端的拦截器可以独立实现，不依赖 Gateway 的具体实现

### 3. 依赖倒置原则（DIP）

- 服务端不直接依赖 URL 查询参数
- 服务端只依赖 HTTP 请求头（Gateway 统一提供）

### 4. 容错性设计

- 双重传递：确保 token 能够到达服务端
- 双重获取：确保服务端能够获取 token
- 降级策略：即使前端实现不完整，也能正常工作

---

## 💡 为什么不能简化？

### 方案1：只用 URL 参数（不行）

```
前端 → URL 参数 → Gateway 提取 → HTTP 请求头 → 服务端获取
```

**问题**：
- 服务端无法从 STOMP header 获取（更直接的方式）
- 如果 Gateway 故障，服务端无法获取 token

### 方案2：只用 STOMP Header（不行）

```
前端 → STOMP Header → 服务端获取
```

**问题**：
- SockJS 握手请求无法在请求头中传递自定义 header
- 浏览器会阻止自定义 header

### 方案3：当前设计（最佳）

```
前端 → [URL 参数 + STOMP Header] → Gateway 提取 → HTTP 请求头 → 服务端[优先STOMP/降级HTTP]
```

**优势**：
- ✅ 绕过浏览器限制（URL 参数）
- ✅ 使用标准协议（STOMP Header）
- ✅ 统一处理（Gateway）
- ✅ 容错性强（双重获取）

---

## 🔍 代码验证

### Gateway 过滤器（提取和转换）

```java
// 从 URL 查询参数提取
String token = exchange.getRequest().getQueryParams().getFirst("access_token");

// 放入 HTTP 请求头
ServerWebExchange mutated = exchange.mutate()
    .request(builder -> builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
    .build();
```

### 服务端拦截器（验证和使用）

```java
// 优先级1：从 STOMP header 获取（更直接）
String auth = firstHeader(accessor, "Authorization");

// 优先级2：从 HTTP 请求头获取（Gateway 放入的，降级）
if (auth == null) {
    auth = request.getHeader("Authorization");
}

// 解析 JWT Token
Jwt jwt = jwtDecoder.decode(token);
accessor.setUser(authentication);
```

---

## ✅ 总结

### 问题1：为什么双重传递？

**答案**：
- **URL 参数**：用于 SockJS HTTP 握手（浏览器限制，无法在请求头传递）
- **STOMP Header**：用于 STOMP CONNECT 帧（协议支持，更直接）
- **降级策略**：即使 STOMP header 中没有，也能从 HTTP 请求头获取（Gateway 放入的）

### 问题2：为什么 Gateway 提取 + 服务端验证？

**答案**：
- **主要目的：降级策略**：如果前端只传递了 URL 参数（没有 STOMP header），Gateway 提取并放入 HTTP 请求头，服务端可以从 HTTP 请求头获取
- **次要目的：统一处理**：Gateway 统一处理所有 WebSocket 请求的 token 提取
- **技术限制**：绕过浏览器 HTTP 请求限制（SockJS 握手无法在请求头传递自定义 header）
- **设计原则**：职责分离，Gateway 负责提取，服务端负责验证

**关键理解**：
- 如果前端在 STOMP header 中传递了 token，服务端可以直接从 STOMP header 获取
- Gateway 这一步**不是必须的**，主要是作为降级策略和统一处理

---

## 🎯 核心结论

### 你的理解是正确的！

**Gateway 解析 URL 查询参数放入请求头，这一步主要是降级策略，不是必须的。**

#### 实际情况

1. **如果前端在 STOMP header 中传递了 token**：
   - 服务端拦截器**优先从 STOMP header 获取** ✅
   - Gateway 这一步**不是必须的**（但也不会有问题，因为服务端优先使用 STOMP header）

2. **如果前端只传递了 URL 参数（没有 STOMP header）**：
   - Gateway 过滤器提取并放入 HTTP 请求头
   - 服务端拦截器从 HTTP 请求头获取（降级方案）✅

#### 设计意图

```
理想情况（前端完整实现）：
  前端 → STOMP Header → 服务端直接获取 ✅
  Gateway 这一步：不必须，但也不影响

降级情况（前端只传 URL 参数）：
  前端 → URL 参数 → Gateway 提取 → HTTP 请求头 → 服务端获取 ✅
  Gateway 这一步：必须，作为降级策略
```

#### 为什么保留 Gateway 这一步？

1. **容错性**：即使前端实现不完整，也能正常工作
2. **统一处理**：Gateway 统一处理所有 WebSocket 请求
3. **技术限制**：绕过浏览器 HTTP 请求限制

---

**文档版本**：v1.1  
**最后更新**：2025-01-XX




















