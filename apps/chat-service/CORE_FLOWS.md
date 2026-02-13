# Chat-Service 核心业务流程

> 用流程图和序列图快速理解 chat-service 的核心业务逻辑

---

## 📋 目录

1. [私聊消息发送流程](#私聊消息发送流程)
2. [会话列表查询流程](#会话列表查询流程)
3. [历史消息查询流程](#历史消息查询流程)
4. [WebSocket 连接建立流程](#websocket-连接建立流程)
5. [未读消息计数流程](#未读消息计数流程)

---

## 1. 私聊消息发送流程

### 流程图

```
┌─────────┐
│  前端   │
└────┬────┘
     │ 1. WebSocket 发送消息
     │    /app/chat.private.send
     ↓
┌─────────────────────┐
│ ChatWsController    │
│ sendPrivate()       │
└────┬────────────────┘
     │ 2. 提取用户信息
     │    从 JWT 获取 senderId
     ↓
┌─────────────────────┐
│ ChatMessagingService│
│ sendPrivateMessage() │
└────┬────────────────┘
     │
     ├─→ 3. 验证好友关系
     │   SystemUserClient.isFriend()
     │   ├─ 成功 → 继续
     │   └─ 失败 → 记录警告（降级策略）
     │
     ├─→ 4. 内容清洗
     │   sanitize() - 去空白、截断500字符
     │
     ├─→ 5. 获取/创建会话
     │   ChatSessionService.getOrCreatePrivateSession()
     │   ├─ 查询 support_key（userId1|userId2）
     │   └─ 不存在则创建新会话 + 两个成员记录
     │
     ├─→ 6. 保存消息到数据库
     │   ChatSessionService.savePrivateMessage()
     │   ├─ 幂等检查：client_op_id 唯一约束
     │   ├─ 已存在 → 返回成功（幂等）
     │   └─ 不存在 → 保存消息 + 更新会话 last_message
     │
     ├─→ 7. WebSocket 点对点推送
     │   messagingTemplate.convertAndSendToUser()
     │   └─ 路由：/user/{targetUserId}/queue/chat.private
     │
     └─→ 8. 写入 Redis 缓存
         ChatHistoryService.appendPrivateMessage()
         └─ Key: chat:private:history:{sessionId}
            └─ 保留最近 100 条，TTL 24h
```

### 关键决策点

| 步骤 | 决策 | 结果 |
|------|------|------|
| 好友验证 | 失败 | 记录警告，允许发送（降级策略） |
| 消息幂等 | `client_op_id` 已存在 | 直接返回成功，不重复保存 |
| 会话不存在 | 首次私聊 | 自动创建会话和成员记录 |

---

## 2. 会话列表查询流程

### 流程图

```
┌─────────┐
│  前端   │
└────┬────┘
     │ 1. HTTP GET /api/sessions
     ↓
┌─────────────────────┐
│ ChatSessionController│
│ listSessions()      │
└────┬────────────────┘
     │ 2. 从 JWT 获取 userId
     ↓
┌─────────────────────┐
│ ChatSessionService  │
│ listUserSessionsWithUserInfo() │
└────┬────────────────┘
     │
     ├─→ 3. 查询用户会话
     │   ├─ 通过成员表：findSessionsByUserId()
     │   └─ 通过消息表：findPrivateSessionsWithMessagesByUserId()
     │   └─ 合并去重（按 sessionId）
     │
     ├─→ 4. 为每个会话计算信息
     │   ├─ 查询成员记录（ChatSessionMember）
     │   ├─ 计算未读数（countUnreadMessages）
     │   ├─ 查询最后一条消息
     │   └─ 对于私聊，查询对方用户ID
     │      ├─ 方法1：从成员表查询
     │      ├─ 方法2：从 support_key 解析
     │      └─ 方法3：从消息表推断
     │
     ├─→ 5. 批量获取用户信息
     │   UserProfileCacheService.batchGet()
     │   ├─ 先查 Redis 缓存（TTL 2h）
     │   └─ 缓存未命中 → Feign 调用 system-service
     │
     └─→ 6. 构建响应
         └─ SessionResponse（包含会话信息 + 用户信息）
```

### 数据查询策略

```
查询用户会话的两种方式：
┌─────────────────────────────────────┐
│ 方式1：通过成员表                     │
│ ChatSessionMember.user_id = ?        │
│ → 适用于：用户主动加入的会话          │
└─────────────────────────────────────┘
           +
┌─────────────────────────────────────┐
│ 方式2：通过消息表                     │
│ ChatMessage.sender_id = ?            │
│ → 适用于：成员表可能缺失的情况        │
│    （历史数据、数据不一致等）         │
└─────────────────────────────────────┘
           ↓
    合并去重（按 sessionId）
```

---

## 3. 历史消息查询流程

### 流程图

```
┌─────────┐
│  前端   │
└────┬────┘
     │ 1. HTTP GET /api/history/private
     │    ?userId1=xxx&userId2=yyy&limit=50
     ↓
┌─────────────────────┐
│ ChatHistoryController│
│ listPrivateMessages()│
└────┬────────────────┘
     │ 2. 构建会话ID（按字典序排序）
     │    sessionId = min(userId1, userId2) + ":" + max(...)
     ↓
┌─────────────────────┐
│ ChatHistoryService  │
│ listPrivateMessages()│
└────┬────────────────┘
     │
     ├─→ 3. 查询 Redis
     │   Key: chat:private:history:{sessionId}
     │   ├─ 存在 → 返回 Redis 数据 ✅
     │   └─ 不存在 → 继续步骤4
     │
     └─→ 4. 查询数据库（Redis 未命中）
         ├─ 4.1 查询会话ID
         │   ChatSessionService.getPrivateSessionId()
         │
         ├─ 4.2 查询消息列表
         │   ChatSessionService.listMessages(sessionId, limit)
         │   └─ 按时间正序返回
         │
         ├─ 4.3 转换为 ChatMessagePayload
         │   └─ 补充 senderName（从缓存获取）
         │
         └─ 4.4 回填 Redis
             └─ 批量写入，设置 TTL 24h
```

### 缓存策略

```
┌─────────────────────────────────────┐
│ Redis 缓存层（快速路径）              │
│ - 存储：最近 100 条私聊消息            │
│ - TTL：24 小时                        │
│ - 格式：JSON 字符串列表                │
└─────────────────────────────────────┘
           ↓ 未命中
┌─────────────────────────────────────┐
│ PostgreSQL 持久化层（慢速路径）        │
│ - 存储：所有历史消息                   │
│ - 查询：按时间正序，最多 limit 条      │
└─────────────────────────────────────┘
           ↓ 回填
┌─────────────────────────────────────┐
│ Redis 缓存（提升后续查询性能）         │
└─────────────────────────────────────┘
```

---

## 4. WebSocket 连接建立流程

### 序列图

```
前端                    WebSocketAuthChannelInterceptor    WebSocketTokenStore    WebSocketSessionManager    SessionRegistry
  │                              │                                │                          │                        │
  │─── WebSocket Connect ───────>│                                │                          │                        │
  │                              │─── 提取 JWT Token ───────────>│                          │                        │
  │                              │<── 存储 Token ────────────────│                          │                        │
  │                              │                                │                          │                        │
  │                              │─── 鉴权通过 ───────────────────────────────────────────>│                        │
  │                              │                                │                          │                        │
  │                              │                                │                          │─── 查询旧连接 ───────>│
  │                              │                                │                          │<── 返回旧连接列表 ────│
  │                              │                                │                          │                        │
  │                              │                                │                          │─── 踢掉旧连接 ────────>│
  │                              │                                │                          │    (同服务)            │
  │                              │                                │                          │                        │
  │                              │                                │                          │─── 注册新连接 ────────>│
  │                              │                                │                          │                        │
  │<─── 连接成功 ────────────────────────────────────────────────────────────────────────────│                        │
  │                              │                                │                          │                        │
  │                              │                                │                          │─── 发送踢人通知 ──────>│
  │<─── 踢人通知（旧连接） ────────────────────────────────────────────────────────────────────│                        │
```

### 关键步骤

1. **鉴权拦截**：`WebSocketAuthChannelInterceptor` 提取 JWT Token
2. **Token 存储**：存储到 `WebSocketTokenStore`（Redis + 内存降级）
3. **连接管理**：`WebSocketSessionManager` 处理连接事件
4. **后连踢前**：新连接建立时，踢掉同服务的旧连接
5. **通知旧连接**：通过 `WebSocketDisconnectHelper` 发送踢人通知

---

## 5. 未读消息计数流程

### 流程图

```
┌─────────┐
│  前端   │
└────┬────┘
     │ 1. HTTP GET /api/sessions/{sessionId}/unread
     ↓
┌─────────────────────┐
│ ChatSessionController│
│ getUnreadCount()    │
└────┬────────────────┘
     │ 2. 从 JWT 获取 userId
     ↓
┌─────────────────────┐
│ ChatSessionService  │
│ countUnreadMessages()│
└────┬────────────────┘
     │
     ├─→ 3. 查询成员记录
     │   ChatSessionMember
     │   ├─ 不存在 → 返回所有未撤回消息数（排除自己）
     │   └─ 存在 → 继续步骤4
     │
     ├─→ 4. 检查 last_read_time
     │   ├─ null → 返回所有未撤回消息数（排除自己）
     │   └─ 有值 → 继续步骤5
     │
     └─→ 5. 计算未读数
         ChatMessageRepository.countUnreadMessagesAfter()
         └─ SQL: COUNT(*) WHERE
              session_id = ?
              AND created_at > last_read_time
              AND sender_id != current_user_id
              AND is_recalled = false
```

### 计算逻辑说明

```sql
-- 未读消息数计算
SELECT COUNT(*) 
FROM chat_message 
WHERE session_id = ? 
  AND created_at > ?  -- last_read_time
  AND sender_id != ?  -- 排除自己发的消息
  AND is_recalled = false  -- 排除已撤回的消息
```

**关键点：**
- 基于时间戳比较，避免子查询性能问题
- 排除自己发的消息（自己发的消息不算未读）
- 排除已撤回的消息

---

## 🔄 消息流转路径对比

### 大厅消息
```
发送者 → /app/chat.lobby.send 
  → /topic/chat.lobby 
    → 所有订阅者（广播）
      → 仅 Redis 缓存，不持久化
```

### 房间消息
```
发送者 → /app/chat.room.send 
  → /topic/chat.room.{roomId} 
    → 房间内所有订阅者（广播）
      → Redis 缓存（最近50条）
      → PostgreSQL 持久化（完整历史）
```

### 私聊消息
```
发送者 → /app/chat.private.send 
  → /user/{targetUserId}/queue/chat.private 
    → 仅目标用户（点对点）
      → Redis 缓存（最近100条）
      → PostgreSQL 持久化（完整历史）
```

---

**文档版本**：v1.0  
**最后更新**：2025-01-XX




















