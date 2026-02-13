# 迁移到原生 WebSocket 完整指南

> 详细说明如何从 SockJS 迁移到原生 WebSocket，以及各层改动情况

---

## ✅ 你的理解完全正确！

如果前端改成原生 WebSocket + STOMP header 传递 token：

1. ✅ **后端无需改动**：服务端拦截器优先从 STOMP header 获取 token，完全正常工作
2. ✅ **不再需要 URL 参数**：原生 WebSocket 连接时不需要 URL 参数，token 只在 STOMP header 中传递
3. ✅ **Gateway 过滤器可以移除**：因为服务端拦截器直接从 STOMP header 获取 token，不需要 Gateway 从 URL 参数提取

---

## 📋 改动清单

### 1. 前端改动 ✅ **必须改动**

#### 1.1 移除 SockJS 依赖

```javascript
// 删除这行
// import SockJS from 'sockjs-client'
```

#### 1.2 改用原生 WebSocket

**改动前（SockJS）**：
```javascript
const wsUrl = `/chat-service/ws?access_token=${encodeURIComponent(token)}`
socket = new SockJS(wsUrl)
stomp = Stomp.over(socket)

const headers = { Authorization: 'Bearer ' + token }
stomp.connect(headers, (frame) => {
  // 连接成功
})
```

**改动后（原生 WebSocket）**：
```javascript
// 原生 WebSocket 连接（不需要 URL 参数）
socket = new WebSocket('wss://gateway/chat-service/ws')
stomp = Stomp.over(socket)

// 在 STOMP CONNECT 帧中传递 token（更安全）
const headers = { Authorization: 'Bearer ' + token }
stomp.connect(headers, (frame) => {
  // 连接成功
})
```

#### 1.3 修改的文件

- `game-hub-web/src/services/ws/chatSocket.js`
- `game-hub-web/src/services/ws/gomokuSocket.js`
- `game-hub-web/package.json`（移除 `sockjs-client` 依赖）

---

### 2. Gateway 改动 ✅ **可以移除**

#### 2.1 移除 WebSocketTokenFilter

**文件**：`apps/gateway/src/main/java/com/gamehub/gateway/filter/WebSocketTokenFilter.java`

**操作**：
- 可以删除整个文件
- 或者保留但注释掉（作为降级策略）

**原因**：
- 前端使用原生 WebSocket + STOMP header 传递 token
- 服务端拦截器直接从 STOMP header 获取 token
- Gateway 过滤器不再需要从 URL 参数提取 token

---

### 3. 后端改动 ✅ **完全不需要改动**

**原因**：
- 后端已经支持原生 WebSocket（`registry.addEndpoint("/ws")`）
- 服务端拦截器优先从 STOMP header 获取 token（已经实现）
- 代码逻辑完全兼容

**验证**：
```java
// WebSocketAuthChannelInterceptor.java
// 优先级1：从 STOMP header 获取（如果前端传递了）
String auth = firstHeader(accessor, "Authorization");

// 优先级2：从 HTTP 请求头获取（Gateway 放入的，降级方案）
if (auth == null) {
    auth = request.getHeader("Authorization");
}
```

**说明**：
- 如果前端在 STOMP header 中传递了 token，服务端会优先使用 ✅
- 即使 Gateway 过滤器移除了，服务端也能正常工作 ✅

---

## 🔄 完整迁移流程

### 步骤1：前端改动

```javascript
// chatSocket.js
// 1. 移除 SockJS 导入
// import SockJS from 'sockjs-client'  // 删除

// 2. 改用原生 WebSocket
async function connectChatWebSocketInternal(isInitialConnect = false) {
  // ... 前置检查 ...
  
  const token = await ensureAuthenticated()
  if (!token) {
    scheduleReconnect(isInitialConnect)
    return
  }

  // 原生 WebSocket 连接（不需要 URL 参数）
  socket = new WebSocket('wss://gateway/chat-service/ws')
  
  socket.onclose = (event) => {
    // ... 处理断开 ...
  }
  
  socket.onerror = (error) => {
    // ... 处理错误 ...
  }

  stomp = Stomp.over(socket)
  
  // 配置心跳
  stomp.heartbeat.outgoing = 5000
  stomp.heartbeat.incoming = 5000

  // 在 STOMP CONNECT 帧中传递 token
  const headers = { Authorization: 'Bearer ' + token }
  stomp.connect(headers, (frame) => {
    // 连接成功
    notifyListeners('onConnect')
  })
}
```

### 步骤2：Gateway 改动（可选）

**选项A：完全移除**
```java
// 删除 WebSocketTokenFilter.java
// 或者注释掉 @Component 注解
```

**选项B：保留作为降级策略**
```java
// 保留代码，但添加注释说明
// 如果所有客户端都使用 STOMP header，此过滤器不再需要
```

### 步骤3：后端验证

**无需改动，但需要验证**：
1. 验证原生 WebSocket 连接正常
2. 验证 STOMP header 中的 token 能被正确解析
3. 验证用户身份设置正确

---

## 🔍 代码验证

### 前端连接流程

```
1. 创建原生 WebSocket 连接
   socket = new WebSocket('wss://gateway/chat-service/ws')
   
2. 创建 STOMP 客户端
   stomp = Stomp.over(socket)
   
3. 在 STOMP CONNECT 帧中传递 token
   stomp.connect({ Authorization: 'Bearer ' + token }, callback)
```

### 后端处理流程

```
1. WebSocket 握手成功（原生 WebSocket）
   
2. STOMP CONNECT 帧到达
   WebSocketAuthChannelInterceptor.preSend()
   
3. 从 STOMP header 获取 token
   String auth = firstHeader(accessor, "Authorization")
   // ✅ 获取成功，不需要从 HTTP 请求头获取
   
4. 解析 JWT Token
   Jwt jwt = jwtDecoder.decode(token)
   
5. 设置用户身份
   accessor.setUser(authentication)
```

---

## ⚠️ 注意事项

### 1. 确保所有客户端都使用 STOMP header

**如果还有其他客户端使用 URL 参数**：
- Gateway 过滤器必须保留
- 或者前端需要统一迁移

### 2. 测试验证

**必须测试的场景**：
1. ✅ 原生 WebSocket 连接成功
2. ✅ STOMP header 中的 token 能被正确解析
3. ✅ 用户身份设置正确
4. ✅ 消息发送和接收正常
5. ✅ 重连机制正常

### 3. 向后兼容性

**如果保留 SockJS 支持**：
- 后端需要同时支持两种方式（已经支持）
- Gateway 过滤器需要保留（处理 SockJS 的 URL 参数）

**如果完全迁移到原生 WebSocket**：
- 可以移除 Gateway 过滤器
- 可以移除后端 SockJS 端点（可选）

---

## 📊 改动对比

### 改动前（SockJS）

```
前端
  ├─→ URL 参数: /ws?access_token=xxx
  └─→ STOMP Header: Authorization: Bearer xxx
  ↓
Gateway (WebSocketTokenFilter)
  ├─→ 从 URL 参数提取 token
  └─→ 放入 HTTP 请求头
  ↓
服务端
  ├─→ [优先] 从 STOMP header 获取 ✅
  └─→ [降级] 从 HTTP 请求头获取（Gateway 放入的）
```

### 改动后（原生 WebSocket）

```
前端
  └─→ STOMP Header: Authorization: Bearer xxx
  ↓
Gateway
  └─→ （不需要处理，直接透传）
  ↓
服务端
  └─→ 从 STOMP header 获取 ✅
```

---

## ✅ 总结

### 你的理解完全正确！

**如果前端改成原生 WebSocket + STOMP header**：

1. ✅ **后端无需改动**：服务端拦截器优先从 STOMP header 获取 token，完全正常工作
2. ✅ **不再需要 URL 参数**：原生 WebSocket 连接时不需要 URL 参数，token 只在 STOMP header 中传递
3. ✅ **Gateway 过滤器可以移除**：因为服务端拦截器直接从 STOMP header 获取 token，不需要 Gateway 从 URL 参数提取

### 改动清单

| 组件 | 需要改动吗？ | 改动内容 |
|------|------------|---------|
| **前端** | ✅ 必须 | 移除 SockJS，改用原生 WebSocket，在 STOMP header 中传递 token |
| **Gateway** | ✅ 可以移除 | 删除 `WebSocketTokenFilter`（如果所有客户端都使用 STOMP header） |
| **后端** | ✅ 不需要 | 完全不需要改动，已经支持 |

---

**文档版本**：v1.0  
**最后更新**：2025-01-XX




















