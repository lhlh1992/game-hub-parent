# Chat-Service 架构概览

> 快速理解 chat-service 的核心架构和业务逻辑，无需深入每个文件细节

---

## 📋 目录

1. [服务定位](#服务定位)
2. [核心功能](#核心功能)
3. [架构分层](#架构分层)
4. [核心流程](#核心流程)
5. [数据模型](#数据模型)
6. [关键技术点](#关键技术点)

---

## 🎯 服务定位

**chat-service** 是平台的**社交域核心服务**，负责所有实时消息通信功能。

### 支持的聊天类型

| 类型 | 描述 | 存储方式 |
|------|------|----------|
| **大厅聊天** | 全局频道，所有在线用户可见 | Redis（仅缓存，不持久化） |
| **房间聊天** | 游戏房间内的实时交流 | Redis（最近50条）+ PostgreSQL（历史） |
| **私聊消息** | 点对点通信，仅双方可见 | Redis（最近100条）+ PostgreSQL（完整历史） |
| **系统通知** | 系统主动推送的通知 | WebSocket 点对点推送 |

---

## 🏗️ 架构分层

```
┌─────────────────────────────────────────────────────────┐
│                    前端 (React)                          │
│  WebSocket (STOMP)  ←→  HTTP REST API                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│              Controller 层 (入口)                        │
│  ┌──────────────┐  ┌──────────────────────────────┐    │
│  │ ChatWsController │  │ ChatSessionController      │    │
│  │ (WebSocket)      │  │ ChatHistoryController      │    │
│  └──────────────┘  └──────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│              Service 层 (业务逻辑)                        │
│  ┌──────────────────┐  ┌──────────────────────────┐     │
│  │ ChatMessagingService │  │ ChatSessionService      │     │
│  │ - 消息发送/广播      │  │ - 会话管理              │     │
│  └──────────────────┘  │ - 未读计数                │     │
│  ┌──────────────────┐  └──────────────────────────┘     │
│  │ ChatHistoryService │  ┌──────────────────────────┐     │
│  │ - Redis缓存管理    │  │ NotificationPushService  │     │
│  │ - 历史查询         │  │ - 系统通知推送           │     │
│  └──────────────────┘  └──────────────────────────┘     │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│          Infrastructure 层 (外部依赖)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ SystemUserClient │  │ Redis        │  │ PostgreSQL   │   │
│  │ (Feign)         │  │ (缓存)        │  │ (持久化)      │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────┘
```

---

## 🔄 核心流程

### 1. 消息发送流程（以私聊为例）

```
用户发送消息
    ↓
ChatWsController.sendPrivate()
    ↓
ChatMessagingService.sendPrivateMessage()
    ├─→ 验证好友关系 (SystemUserClient.isFriend)
    ├─→ 内容清洗 (sanitize)
    ├─→ 获取/创建会话 (ChatSessionService.getOrCreatePrivateSession)
    ├─→ 保存到数据库 (ChatSessionService.savePrivateMessage)
    │   └─→ 幂等检查 (clientOpId)
    ├─→ WebSocket 点对点推送 (messagingTemplate.convertAndSendToUser)
    └─→ 写入 Redis 缓存 (ChatHistoryService.appendPrivateMessage)
```

**关键点：**
- 私聊需要验证好友关系
- 使用 `clientOpId` 实现消息幂等（防止重复发送）
- 数据库是主存储，Redis 是缓存层
- 推送使用 `/user/{userId}/queue/chat.private` 确保私密性

### 2. 会话管理流程

```
用户查询会话列表
    ↓
ChatSessionController.listSessions()
    ↓
ChatSessionService.listUserSessionsWithUserInfo()
    ├─→ 查询用户参与的会话（通过成员表 + 消息表）
    ├─→ 计算未读消息数（基于 last_read_time）
    ├─→ 查询最后一条消息
    ├─→ 批量获取用户信息（UserProfileCacheService）
    └─→ 返回完整会话信息
```

**关键点：**
- 私聊会话通过 `support_key` 去重（格式：`min(userId1,userId2)|max(userId1,userId2)`）
- 未读计数基于时间戳比较，排除自己发的消息
- 用户信息使用 Redis 缓存（TTL 2小时）

### 3. 历史消息查询流程

```
用户查询历史消息
    ↓
ChatHistoryController.listPrivateMessages()
    ↓
ChatHistoryService.listPrivateMessages()
    ├─→ 先查 Redis（快速路径）
    ├─→ Redis 为空 → 查数据库
    ├─→ 数据库查询结果回填 Redis
    └─→ 返回消息列表
```

**关键点：**
- Redis 存储最近 100 条私聊消息（房间 50 条）
- Redis 未命中时从数据库回填，提升后续查询性能
- 消息按时间正序返回

### 4. WebSocket 连接管理

```
用户建立 WebSocket 连接
    ↓
WebSocketAuthChannelInterceptor（鉴权）
    ├─→ 提取 JWT Token
    └─→ 存储到 WebSocketTokenStore（Redis + 内存降级）
    ↓
WebSocketSessionManager.handleSessionConnect()
    ├─→ 注册新会话到 SessionRegistry
    ├─→ 踢掉同服务的旧连接（后连踢前）
    └─→ 发送踢人通知给旧连接
```

**关键点：**
- 同一用户在同一服务只能有一个 WebSocket 连接
- Token 存储在 Redis，支持多实例部署
- 支持通过 `loginSessionId`（sid）获取 Token，适配 Token 刷新场景

---

## 📊 数据模型

### 核心实体关系

```
ChatSession (会话)
    ├─→ ChatSessionMember (会话成员) [1:N]
    └─→ ChatMessage (消息) [1:N]
```

### 实体说明

#### ChatSession（会话表）
- **类型**：`PRIVATE`（私聊）、`ROOM`（房间）、`GROUP`（群聊）
- **support_key**：私聊会话唯一键，格式：`userId1|userId2`（按字典序排序）
- **room_id**：房间聊天时关联的房间ID
- **last_message_id / last_message_time**：最后一条消息信息

#### ChatMessage（消息表）
- **session_id**：所属会话
- **sender_id**：发送者ID
- **client_op_id**：客户端操作ID（用于幂等，唯一约束）
- **content**：消息内容
- **is_recalled**：是否已撤回
- **message_type**：`TEXT`、`IMAGE`、`FILE`、`SYSTEM`

#### ChatSessionMember（会话成员表）
- **session_id + user_id**：唯一约束
- **last_read_message_id / last_read_time**：最后已读位置
- **left_at**：离开时间（软删除）

---

## 🔧 关键技术点

### 1. WebSocket + STOMP

**端点配置：**
- 连接端点：`/ws`（支持 SockJS）
- 消息代理：`/topic`（广播）、`/queue`（点对点）、`/user`（用户专属）
- 应用前缀：`/app`

**消息路由：**
- 大厅：`/app/chat.lobby.send` → `/topic/chat.lobby`
- 房间：`/app/chat.room.send` → `/topic/chat.room.{roomId}`
- 私聊：`/app/chat.private.send` → `/user/{userId}/queue/chat.private`

### 2. 数据存储策略

| 数据类型 | Redis | PostgreSQL | 说明 |
|---------|-------|-----------|------|
| 大厅消息 | ✅ 缓存 | ❌ | 不持久化，仅实时推送 |
| 房间消息 | ✅ 最近50条 | ✅ 完整历史 | Redis 用于快速查询 |
| 私聊消息 | ✅ 最近100条 | ✅ 完整历史 | Redis 用于快速查询 |
| 会话信息 | ❌ | ✅ | 完整持久化 |
| 用户信息 | ✅ 缓存2h | ❌ | 减少 Feign 调用 |

### 3. 幂等性保证

- **私聊消息**：使用 `client_op_id`（前端生成 UUID）实现幂等
- **数据库约束**：`uk_chat_message_client_op` 唯一约束
- **处理逻辑**：重复消息直接返回成功，不重复保存

### 4. 未读消息计数

**计算逻辑：**
```sql
未读数 = COUNT(*) WHERE 
    session_id = ? 
    AND created_at > last_read_time 
    AND sender_id != current_user_id
    AND is_recalled = false
```

**关键点：**
- 基于时间戳比较，避免子查询性能问题
- 排除自己发的消息
- 排除已撤回的消息

### 5. 外部服务调用

**SystemUserClient（Feign）：**
- `getUserInfo(userId)`：获取用户信息
- `isFriend(userId1, userId2)`：验证好友关系
- 使用 Resilience4j 熔断器，降级策略允许发送（生产环境建议改为拒绝）

### 6. Kafka 集成

**RoomEventConsumer：**
- 订阅房间事件（创建/删除）
- 房间删除时清理 Redis 中的房间聊天记录
- **当前状态**：已实现但未启用（等待 game-service 发布事件）

---

## 📁 关键文件速查

| 文件 | 职责 |
|------|------|
| `ChatWsController` | WebSocket 消息入口（大厅/房间/私聊发送） |
| `ChatSessionController` | HTTP REST 接口（会话列表/消息查询/已读标记） |
| `ChatMessagingServiceImpl` | 消息发送核心逻辑（验证/广播/持久化） |
| `ChatSessionServiceImpl` | 会话管理（创建/查询/未读计数） |
| `ChatHistoryServiceImpl` | 历史消息管理（Redis 缓存 + 数据库回填） |
| `WebSocketSessionManager` | WebSocket 连接管理（注册/踢人） |
| `WebSocketAuthChannelInterceptor` | WebSocket 鉴权拦截器 |
| `WebSocketTokenStore` | Token 存储（Redis + 内存降级） |

---

## 🚀 快速理解路径

1. **先看入口**：`ChatWsController` → 了解消息如何接收
2. **再看服务**：`ChatMessagingServiceImpl` → 了解消息如何处理
3. **最后看数据**：`ChatSession` / `ChatMessage` → 了解数据如何存储

**推荐阅读顺序：**
```
ChatWsController 
  → ChatMessagingServiceImpl 
    → ChatSessionServiceImpl 
      → ChatHistoryServiceImpl
        → Entity (ChatSession, ChatMessage, ChatSessionMember)
```

---

## 💡 设计亮点

1. **分层清晰**：Controller → Service → Repository，职责明确
2. **缓存策略**：Redis 缓存 + PostgreSQL 持久化，兼顾性能和可靠性
3. **幂等保证**：通过 `client_op_id` 防止消息重复
4. **连接管理**：后连踢前，确保单用户单连接
5. **降级策略**：Redis 故障时使用内存降级，Feign 调用失败时记录日志

---

**文档版本**：v1.0  
**最后更新**：2025-01-XX




















