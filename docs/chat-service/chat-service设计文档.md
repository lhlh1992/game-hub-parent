# Chat Service 设计文档

> 本文档定义 chat-service 的服务边界、职责、与其他服务的交互方式，以及与前端交互方式。

---

## 一、服务定位与边界

### 1.1 服务定位

**chat-service** 是独立的**社交域服务**，负责平台内所有聊天相关功能。

### 1.2 核心职责

chat-service **负责**：

1. **消息收发**
   - 接收用户发送的消息（文本、图片、文件等）
   - 广播消息到订阅者（大厅、房间、私聊等）
   - 消息格式化和验证

2. **会话管理**
   - 创建/查询聊天会话（大厅、房间、私聊）
   - 管理会话成员
   - 会话生命周期管理

3. **消息持久化**
   - 近期消息存储（Redis，用于快速加载）
   - 历史消息归档（PostgreSQL）
   - 消息检索（全文搜索）

4. **权限控制**
   - 禁言功能（管理员可禁言用户）
   - 敏感词过滤
   - 消息发送频率限制（防刷屏）

5. **实时通知**
   - 新消息通知
   - 未读消息计数
   - 在线状态同步

### 1.3 不负责的内容

chat-service **不负责**：

1. ❌ **用户管理**：不管理用户注册/登录/档案（由 system-service 负责）
2. ❌ **房间管理**：不创建/删除房间（由 game-service 负责）
3. ❌ **游戏逻辑**：不处理游戏状态和游戏事件（由 game-service 负责）

---

## 二、服务间交互设计（低耦合）

### 2.1 与 system-service 交互

**交互方式**：同步调用（Feign Client）+ 熔断降级

**用途**：
- 查询用户信息（昵称、头像、在线状态）
- 查询用户权限（是否被禁言）

**接口设计**：
```java
@FeignClient(name = "system-service", fallback = SystemUserClientFallback.class)
public interface SystemUserClient {
    @GetMapping("/api/users/{userId}/info")
    UserInfo getUserInfo(@PathVariable String userId);
    
    @GetMapping("/api/users/{userId}/muted")
    boolean isUserMuted(@PathVariable String userId);
}
```

**低耦合策略**：
- ✅ 使用 Feign Client，通过服务名调用（支持 K8s DNS）
- ✅ 添加熔断降级（Resilience4j），system-service 不可用时返回默认值
- ✅ 缓存用户信息（Redis，TTL 5分钟），减少调用频率

### 2.2 与 game-service 交互

**交互方式**：Kafka 事件（异步解耦）+ 可选 Feign Client（同步查询）

#### 方式 1：Kafka 事件（推荐，低耦合）

**用途**：
- 房间创建事件 → chat-service 自动创建房间聊天会话
- 房间删除事件 → chat-service 自动清理房间聊天会话
- 房间成员变化事件 → chat-service 更新会话成员

**事件定义**：
```java
// game-service 发布事件
public class RoomCreatedEvent {
    private String roomId;
    private String ownerUserId;
    private Long createdAt;
}

public class RoomDeletedEvent {
    private String roomId;
}

public class RoomMemberJoinedEvent {
    private String roomId;
    private String userId;
}

public class RoomMemberLeftEvent {
    private String roomId;
    private String userId;
}
```

**chat-service 消费**：
```java
@KafkaListener(topics = "room-events", groupId = "chat-service-group")
public void handleRoomEvent(RoomEvent event) {
    switch (event.getType()) {
        case "ROOM_CREATED":
            chatService.createRoomSession(event.getRoomId());
            break;
        case "ROOM_DELETED":
            chatService.deleteRoomSession(event.getRoomId());
            break;
        // ...
    }
}
```

**低耦合策略**：
- ✅ 异步事件驱动，game-service 和 chat-service 完全解耦
- ✅ game-service 不需要知道 chat-service 的存在
- ✅ chat-service 故障不影响 game-service

#### 方式 2：Feign Client（可选，用于实时查询）

**用途**：
- 查询房间成员列表（用于房间聊天权限校验）

**接口设计**：
```java
@FeignClient(name = "game-service", fallback = GameRoomClientFallback.class)
public interface GameRoomClient {
    @GetMapping("/api/game/rooms/{roomId}/members")
    List<String> getRoomMembers(@PathVariable String roomId);
}
```

**低耦合策略**：
- ✅ 添加熔断降级，game-service 不可用时返回空列表
- ✅ 缓存房间成员（Redis，TTL 30秒），减少调用频率

### 2.3 与 gateway 交互

**交互方式**：WebSocket 路由

**用途**：
- Gateway 将 WebSocket 连接路由到 chat-service

**路由规则**：
```
前端连接：ws://gateway:8080/chat-service/ws?access_token=xxx
Gateway 路由：转发到 chat-service:8083/ws
```

**低耦合策略**：
- ✅ Gateway 通过服务名路由（支持 K8s DNS）
- ✅ chat-service 独立处理 WebSocket 连接，不依赖 Gateway 逻辑

---

## 三、与前端交互设计

### 3.1 WebSocket STOMP（实时消息）

**决策**：
- chat-service 采用 **WebSocket + STOMP**
- 前端保持 **两条长连接**：一条连 game-service（游戏事件），一条连 chat-service（聊天/通知），均经由 Gateway 路由
- 单设备登录（后连踢前）由 SessionRegistry 按 `loginSessionId + service` 维度统一管理，能分别踢掉 game/chat 的 WS
- 技术栈：**Spring MVC + WebSocket/STOMP**（与 game-service 保持一致，生态成熟、开发调试成本低；并发瓶颈可通过线程池调优与水平扩展解决，若未来单实例需 >5000 连接再评估 WebFlux）

**连接地址**：
```
ws://gateway:8080/chat-service/ws?access_token={token}
或
http://gateway:8080/chat-service/ws?access_token={token} (SockJS)
```

**消息格式**：

#### 客户端发送（`/app/chat.*`）

**发送消息**：
```javascript
// 大厅聊天
stompClient.send('/app/chat.lobby.send', {}, JSON.stringify({
    content: 'Hello, world!',
    clientOpId: 'uuid-xxx' // 幂等键
}));

// 房间聊天
stompClient.send('/app/chat.room.send', {}, JSON.stringify({
    roomId: 'room-123',
    content: 'Nice move!',
    clientOpId: 'uuid-xxx'
}));
```

**订阅消息**：
```javascript
// 订阅大厅聊天
stompClient.subscribe('/topic/chat.lobby', (message) => {
    const msg = JSON.parse(message.body);
    // { id, senderId, senderName, senderAvatar, content, timestamp, type }
});

// 订阅房间聊天
stompClient.subscribe('/topic/chat.room.{roomId}', (message) => {
    const msg = JSON.parse(message.body);
    // { id, roomId, senderId, senderName, senderAvatar, content, timestamp, type }
});
```

#### 服务端广播（`/topic/chat.*`）

**消息格式**：
```json
{
    "id": "msg-uuid",
    "sessionId": "lobby|room.{roomId}|private.{userId1}.{userId2}",
    "sessionType": "LOBBY|ROOM|PRIVATE",
    "senderId": "user-uuid",
    "senderName": "玩家昵称",
    "senderAvatar": "https://...",
    "content": "消息内容",
    "messageType": "TEXT|IMAGE|FILE|SYSTEM",
    "timestamp": 1234567890,
    "extraData": {}
}
```

### 3.2 REST API（历史消息、会话列表）

**基础路径**：`/api/chat`

**接口列表**：

1. **获取大厅历史消息**
   ```
   GET /api/chat/lobby/messages
   Query: ?page=0&size=50
   Response: { messages: [...], total: 100 }
   ```

2. **获取房间历史消息**
   ```
   GET /api/chat/rooms/{roomId}/messages
   Query: ?page=0&size=50
   Response: { messages: [...], total: 50 }
   ```

3. **获取会话列表**
   ```
   GET /api/chat/sessions
   Response: { sessions: [...] }
   ```

4. **获取未读消息数**
   ```
   GET /api/chat/unread/count
   Response: { count: 5 }
   ```

5. **标记消息已读**
   ```
   POST /api/chat/sessions/{sessionId}/read
   ```

---

## 四、数据存储设计

### 4.1 Redis（近期消息、会话状态）

**键空间设计**：

1. **大厅消息列表**（ZSET，按时间排序）
   ```
   chat:lobby:messages → ZSET
   Score: timestamp
   Member: messageId
   TTL: 7天
   ```

2. **房间消息列表**（ZSET，按时间排序）
   ```
   chat:room:{roomId}:messages → ZSET
   Score: timestamp
   Member: messageId
   TTL: 7天
   ```

3. **消息内容**（HASH）
   ```
   chat:message:{messageId} → HASH
   Fields: id, senderId, content, timestamp, type, extraData
   TTL: 7天
   ```

4. **会话信息**（HASH）
   ```
   chat:session:{sessionId} → HASH
   Fields: id, type, roomId, memberCount, lastMessageId, lastMessageTime
   TTL: 48小时
   ```

5. **用户禁言状态**（String）
   ```
   chat:user:{userId}:muted → String (timestamp)
   TTL: 动态（禁言时长）
   ```

6. **用户发送频率限制**（String，滑动窗口）
   ```
   chat:user:{userId}:rate → String (count)
   TTL: 1分钟
   ```

### 4.2 PostgreSQL（历史消息归档）

**表结构**（使用蓝图中的设计）：

1. **chat_session**：会话表
2. **chat_session_member**：会话成员表
3. **chat_message**：消息表
4. **chat_message_attachment**：消息附件表

**归档策略**：
- Redis 中的消息保留 7 天
- 超过 7 天的消息自动归档到 PostgreSQL
- 查询历史消息时，优先从 Redis 查询，不足时从 PostgreSQL 查询

---

## 五、服务架构设计

### 5.1 包结构

```
com.gamehub.chatservice
├── ChatServiceApplication          # 启动类
├── config/                         # 配置类
│   ├── SecurityConfig             # Spring Security + OAuth2
│   ├── WebSocketStompConfig       # WebSocket STOMP 配置
│   ├── RedisConfig                # Redis 配置
│   └── KafkaConfig                # Kafka 配置
├── controller/                     # 控制器层
│   ├── ChatRestController         # REST API
│   └── ChatWsController           # WebSocket STOMP 控制器
├── service/                        # 服务层
│   ├── ChatService                # 聊天服务接口
│   ├── SessionService             # 会话服务接口
│   └── impl/                      # 实现类
├── domain/                         # 领域层
│   ├── model/                     # 领域模型
│   │   ├── ChatMessage
│   │   ├── ChatSession
│   │   └── ChatSessionMember
│   └── repository/                # 仓储接口
│       ├── ChatMessageRepository
│       └── ChatSessionRepository
├── infrastructure/                # 基础设施层
│   ├── redis/                     # Redis 实现
│   │   ├── RedisChatMessageRepository
│   │   └── RedisChatSessionRepository
│   ├── postgresql/                # PostgreSQL 实现
│   │   ├── JpaChatMessageRepository
│   │   └── JpaChatSessionRepository
│   ├── client/                    # 外部服务客户端
│   │   ├── SystemUserClient       # Feign Client
│   │   └── GameRoomClient         # Feign Client（可选）
│   └── kafka/                     # Kafka 事件处理
│       ├── RoomEventConsumer      # 消费房间事件
│       └── RoomEventPublisher     # 发布房间事件（可选）
└── common/                         # 通用组件
    ├── exception/                 # 异常定义
    └── dto/                       # DTO
```

### 5.2 关键类职责

| 类 | 职责 |
|---|------|
| `ChatServiceApplication` | Spring Boot 启动入口 |
| `ChatWsController` | WebSocket STOMP 消息处理（发送消息、订阅会话） |
| `ChatRestController` | REST API（历史消息、会话列表） |
| `ChatService` | 核心业务逻辑（发送消息、创建会话、权限校验） |
| `SessionService` | 会话管理（创建/查询会话、管理成员） |
| `ChatMessageRepository` | 消息存储接口（Redis + PostgreSQL） |
| `ChatSessionRepository` | 会话存储接口（Redis + PostgreSQL） |
| `SystemUserClient` | 调用 system-service 获取用户信息 |
| `RoomEventConsumer` | 消费 game-service 发布的房间事件 |

---

## 六、低耦合设计原则

### 6.1 事件驱动（异步解耦）

**原则**：通过 Kafka 事件实现服务间异步解耦

**示例**：
- game-service 发布 `RoomCreatedEvent` → chat-service 自动创建房间聊天会话
- game-service 不需要知道 chat-service 的存在
- chat-service 故障不影响 game-service

### 6.2 服务发现（动态路由）

**原则**：通过服务名调用，不硬编码 IP

**实现**：
- Feign Client 使用服务名（`system-service`、`game-service`）
- Gateway 通过服务名路由 WebSocket
- 支持 K8s DNS 自动解析

### 6.3 熔断降级（容错处理）

**原则**：外部服务不可用时，返回默认值，不阻塞主流程

**实现**：
- Feign Client 配置 Resilience4j 熔断
- 降级策略：返回默认用户信息、空列表等
- 记录日志，便于排查

### 6.4 缓存策略（减少依赖）

**原则**：缓存外部服务数据，减少调用频率

**实现**：
- 用户信息缓存（Redis，TTL 5分钟）
- 房间成员缓存（Redis，TTL 30秒）
- 禁言状态缓存（Redis，TTL 动态）

### 6.5 幂等性（防止重复）

**原则**：关键操作支持幂等，防止重复处理

**实现**：
- 消息发送使用 `clientOpId` 幂等键
- 会话创建使用唯一索引防止重复
- Kafka 事件消费支持幂等处理

---

## 七、部署与配置

### 7.1 服务端口

- **开发环境**：`8083`
- **Docker Compose**：`8083`
- **K8s**：通过 Service 暴露

### 7.2 配置文件

**application.yml**：
```yaml
server:
  port: 8083

spring:
  application:
    name: chat-service
  # OAuth2 Resource Server
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8180/realms/my-realm
  # Redis
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: 6379
      password: ${REDIS_PASSWORD:zaqxsw}
      database: 3  # chat-service 使用 database 3
  # PostgreSQL
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:127.0.0.1}:5432/gamehub_db
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  # Kafka
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: chat-service-group

# 外部服务地址（Feign Client）
system-service:
  url: http://system-service:8082
game-service:
  url: http://game-service:8081
```

### 7.3 Gateway 路由配置

**gateway/application.yml**：
```yaml
spring:
  cloud:
    gateway:
      routes:
        # Chat Service WebSocket
        - id: chat-service-ws
          uri: ws://chat-service:8083
          predicates:
            - Path=/chat-service/ws
          filters:
            - StripPrefix=1
        # Chat Service REST API
        - id: chat-service-api
          uri: http://chat-service:8083
          predicates:
            - Path=/api/chat/**
```

---

## 八、实施步骤

### 阶段 1：基础架构（1-2天）
1. 创建 chat-service 项目结构
2. 配置 Spring Boot、WebSocket、Redis、PostgreSQL
3. 配置 OAuth2 Resource Server
4. 配置 Feign Client（system-service）
5. 配置 Kafka Consumer（房间事件）

### 阶段 2：核心功能（3-5天）
1. 实现大厅聊天（发送、接收、历史消息）
2. 实现房间聊天（发送、接收、历史消息）
3. 实现消息持久化（Redis + PostgreSQL）
4. 实现权限控制（禁言、频率限制）

### 阶段 3：前端对接（2-3天）
1. 修改 `GlobalChat.jsx` 接入后端
2. 修改 `GameRoomPage.jsx` 接入后端
3. 实现消息实时推送
4. 实现历史消息加载

### 阶段 4：优化与测试（2-3天）
1. 性能优化（缓存、批量查询）
2. 异常处理与降级
3. 单元测试与集成测试
4. 文档完善

---

## 九、总结

### 9.1 服务边界

- ✅ **负责**：消息收发、会话管理、消息持久化、权限控制
- ❌ **不负责**：用户管理、房间管理、游戏逻辑

### 9.2 低耦合策略

1. **事件驱动**：通过 Kafka 事件实现异步解耦
2. **服务发现**：通过服务名调用，不硬编码 IP
3. **熔断降级**：外部服务不可用时返回默认值
4. **缓存策略**：减少外部服务调用频率
5. **幂等性**：防止重复处理

### 9.3 交互方式

- **与 system-service**：Feign Client（同步）+ 缓存
- **与 game-service**：Kafka 事件（异步）+ 可选 Feign Client（同步查询）
- **与 gateway**：WebSocket 路由
- **与前端**：WebSocket STOMP（实时）+ REST API（历史消息）

