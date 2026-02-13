# Chat-Service 快速参考

> 关键 API、配置、数据结构速查表

---

## 📡 WebSocket API

### 连接端点
```
ws://host:port/ws
ws://host:port/ws (SockJS)
```

### 订阅主题

| 主题 | 说明 | 示例 |
|------|------|------|
| `/topic/chat.lobby` | 大厅聊天（全局广播） | 所有在线用户可见 |
| `/topic/chat.room.{roomId}` | 房间聊天 | `/topic/chat.room.123e4567-e89b-12d3-a456-426614174000` |
| `/user/{userId}/queue/chat.private` | 私聊消息（点对点） | 仅目标用户接收 |

### 发送消息

| 路由 | 说明 | 请求体 |
|------|------|--------|
| `/app/chat.lobby.send` | 发送大厅消息 | `{ "content": "消息内容" }` |
| `/app/chat.room.send` | 发送房间消息 | `{ "roomId": "房间ID", "content": "消息内容" }` |
| `/app/chat.private.send` | 发送私聊消息 | `{ "targetUserId": "目标用户ID", "content": "消息内容", "clientOpId": "操作ID（可选）" }` |

---

## 🌐 HTTP REST API

### 会话管理

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| `GET` | `/api/sessions` | 查询会话列表（含未读数） | `List<SessionResponse>` |
| `GET` | `/api/sessions/{sessionId}/messages` | 查询会话消息 | `List<MessageResponse>` |
| `GET` | `/api/sessions/{sessionId}/unread` | 查询未读消息数 | `UnreadCountResponse` |
| `POST` | `/api/sessions/{sessionId}/read` | 标记消息已读 | `200 OK` |
| `GET` | `/api/sessions/private/{otherUserId}` | 查询私聊会话ID | `SessionIdResponse` |

### 历史消息

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| `GET` | `/api/history/lobby` | 查询大厅历史（暂不支持） | - |
| `GET` | `/api/history/room/{roomId}` | 查询房间历史 | `List<ChatMessagePayload>` |
| `GET` | `/api/history/private` | 查询私聊历史 | `List<ChatMessagePayload>` |

### 内部接口

| 方法 | 路径 | 说明 | 调用方 |
|------|------|------|--------|
| `POST` | `/api/internal/notify` | 系统通知推送 | system-service |

---

## 📦 数据结构

### ChatMessagePayload（WebSocket 消息格式）

```json
{
  "type": "LOBBY | ROOM | PRIVATE",
  "roomId": "房间ID（房间消息时）",
  "senderId": "发送者ID",
  "senderName": "发送者昵称",
  "targetUserId": "目标用户ID（私聊消息时）",
  "content": "消息内容",
  "timestamp": 1234567890123,
  "clientOpId": "客户端操作ID（可选）"
}
```

### SessionResponse（会话列表响应）

```json
{
  "sessionId": "会话ID",
  "sessionType": "PRIVATE | ROOM | GROUP",
  "sessionName": "会话名称",
  "lastMessage": "最后一条消息内容",
  "lastMessageTime": "2025-01-01T00:00:00Z",
  "unreadCount": 5,
  "otherUserId": "对方用户ID（私聊时）",
  "otherUserNickname": "对方昵称（私聊时）",
  "otherUserAvatarUrl": "对方头像（私聊时）"
}
```

### MessageResponse（消息列表响应）

```json
{
  "messageId": "消息ID",
  "sessionId": "会话ID",
  "senderId": "发送者ID",
  "messageType": "TEXT | IMAGE | FILE | SYSTEM",
  "content": "消息内容",
  "createdAt": "2025-01-01T00:00:00Z",
  "isRecalled": false
}
```

---

## 🗄️ 数据库表结构

### chat_session（会话表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `session_type` | VARCHAR(20) | PRIVATE / ROOM / GROUP |
| `support_key` | VARCHAR(120) | 私聊会话唯一键 |
| `room_id` | UUID | 房间ID（房间聊天时） |
| `last_message_id` | UUID | 最后一条消息ID |
| `last_message_time` | TIMESTAMP | 最后消息时间 |
| `member_count` | INTEGER | 成员数量 |

**索引：**
- `idx_chat_session_type` (session_type)
- `uk_chat_private_key` (support_key) - 唯一约束

### chat_message（消息表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `session_id` | UUID | 会话ID |
| `sender_id` | UUID | 发送者ID |
| `client_op_id` | VARCHAR(64) | 客户端操作ID（幂等） |
| `message_type` | VARCHAR(20) | TEXT / IMAGE / FILE / SYSTEM |
| `content` | TEXT | 消息内容 |
| `is_recalled` | BOOLEAN | 是否已撤回 |
| `created_at` | TIMESTAMP | 创建时间 |

**索引：**
- `idx_chat_message_session` (session_id)
- `idx_chat_message_session_time` (session_id, created_at)
- `uk_chat_message_client_op` (client_op_id) - 唯一约束

### chat_session_member（会话成员表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `session_id` | UUID | 会话ID |
| `user_id` | UUID | 用户ID |
| `last_read_message_id` | UUID | 最后已读消息ID |
| `last_read_time` | TIMESTAMP | 最后已读时间 |
| `left_at` | TIMESTAMP | 离开时间（软删除） |

**索引：**
- `idx_session_member_user_session` (user_id, session_id)
- `uk_session_member` (session_id, user_id) - 唯一约束

---

## 🔧 Redis Key 规范

### 房间消息历史
```
Key: chat:room:history:{roomId}
Type: List
TTL: 24h
Max Size: 50条
```

### 私聊消息历史
```
Key: chat:private:history:{sessionId}
Type: List
TTL: 24h
Max Size: 100条
Format: sessionId = min(userId1, userId2) + ":" + max(userId1, userId2)
```

### WebSocket Token 存储
```
Key: ws:token:{sessionId} 或 ws:token:sid:{loginSessionId}
Type: String
TTL: 根据配置
```

### 用户信息缓存
```
Key: user:profile:{userId}
Type: Hash
TTL: 2h
Fields: userId, username, nickname, avatarUrl
```

---

## ⚙️ 配置项

### application.yml 关键配置

```yaml
spring:
  websocket:
    # WebSocket 心跳间隔（毫秒）
    heartbeat: 5000
  
  redis:
    # Redis 连接配置
    host: localhost
    port: 6379

chat:
  # 房间消息最大缓存条数
  room-history-max-size: 50
  # 私聊消息最大缓存条数
  private-history-max-size: 100
  # 历史记录 TTL（秒）
  history-ttl-seconds: 86400
```

---

## 🔐 安全机制

### WebSocket 鉴权
1. **连接时**：`WebSocketAuthChannelInterceptor` 提取 JWT Token
2. **Token 存储**：`WebSocketTokenStore`（Redis + 内存降级）
3. **消息发送**：从 JWT 提取用户信息（`sha.getUser().getName()`）

### 私聊消息验证
- **好友关系验证**：发送前调用 `SystemUserClient.isFriend()`
- **降级策略**：验证失败时记录警告，允许发送（生产环境建议改为拒绝）

### 幂等性保证
- **client_op_id**：前端生成 UUID，数据库唯一约束
- **重复消息**：直接返回成功，不重复保存

---

## 🚨 常见问题

### Q: 为什么私聊消息需要验证好友关系？
A: 防止非好友用户发送骚扰消息。当前实现中，验证失败会记录警告但仍允许发送（降级策略），生产环境建议改为拒绝。

### Q: 消息幂等是如何实现的？
A: 通过 `client_op_id`（客户端操作ID）实现。前端生成 UUID，数据库设置唯一约束。重复消息会触发唯一约束冲突，服务端捕获后直接返回成功。

### Q: Redis 和 PostgreSQL 的数据一致性如何保证？
A: 
- **写入**：先写 PostgreSQL，再写 Redis（Redis 失败不影响主流程）
- **读取**：先查 Redis，未命中时查 PostgreSQL 并回填 Redis
- **TTL**：Redis 数据有过期时间，定期清理

### Q: 未读消息数如何计算？
A: 基于时间戳比较：`COUNT(*) WHERE created_at > last_read_time AND sender_id != current_user_id AND is_recalled = false`

### Q: WebSocket 连接断开后如何清理？
A: `WebSocketSessionManager` 监听 `SessionDisconnectEvent`，自动清理 `SessionRegistry` 和 `WebSocketTokenStore` 中的记录。

---

## 📚 相关文档

- [架构概览](./ARCHITECTURE_OVERVIEW.md) - 整体架构和设计思路
- [核心流程](./CORE_FLOWS.md) - 详细业务流程和序列图
- [技术文档](../docs/zh/chat-service/chat-service技术文档.md) - 完整技术文档（详细版）

---

**文档版本**：v1.0  
**最后更新**：2025-01-XX




















