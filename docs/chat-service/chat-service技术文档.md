# Chat-Service 技术文档

> 本文档详细说明 chat-service 的技术实现，包括代码架构、功能实现、配置说明、WebSocket 实现、Feign 调用机制、数据持久化等。

---

## 目录

1. [服务概述](#一服务概述)
2. [代码架构](#二代码架构)
3. [配置说明](#三配置说明)
4. [WebSocket 实现](#四websocket-实现)
5. [OpenFeign 配置与使用](#五openfeign-配置与使用)
6. [房间会话实现](#六房间会话实现)
7. [全局消息通知能力](#七全局消息通知能力)
8. [私聊消息会话](#八私聊消息会话)
9. [数据持久化](#九数据持久化)
10. [Redis 缓存查询](#十redis-缓存查询)
11. [前端交互](#十一前端交互)

---

## 一、服务概述

### 1.1 服务定位

**chat-service** 是平台中的**社交域核心服务**，负责所有与"消息通信"相关的功能，包括：

- **大厅聊天**：全局频道，所有在线用户可见
- **房间聊天**：游戏房间内的实时交流
- **私聊消息**：点对点通信，仅双方可见
- **系统通知**：系统主动推送的通知消息

### 1.2 技术栈

- **框架**：Spring Boot 3.x + Spring WebSocket + STOMP
- **安全**：Spring Security + OAuth2 Resource Server (Keycloak JWT)
- **数据存储**：PostgreSQL (JPA/Hibernate) + Redis
- **服务调用**：Spring Cloud OpenFeign + Resilience4j 熔断
- **消息队列**：Kafka (房间事件订阅)
- **服务发现**：Spring Cloud LoadBalancer

### 1.3 核心职责

| 功能模块 | 职责 |
|---------|------|
| 消息收发 | 接收用户消息，广播到订阅者，消息格式化和验证 |
| 会话管理 | 创建/查询聊天会话，管理会话成员，会话生命周期 |
| 消息持久化 | Redis 存储近期消息，PostgreSQL 存储历史消息 |
| 权限控制 | 禁言功能，敏感词过滤，发送频率限制 |
| 实时通知 | 新消息通知，未读消息计数，在线状态同步 |

---

## 二、代码架构

### 2.1 包结构

```
com.gamehub.chatservice
├── ChatServiceApplication          # 启动类
├── config/                         # 配置类
│   ├── SecurityConfig             # Spring Security + OAuth2
│   ├── WebSocketStompConfig       # WebSocket STOMP 配置
│   ├── WebSocketAuthChannelInterceptor  # WebSocket 鉴权拦截器
│   ├── WebSocketTokenStore        # Token 存储（Redis + 内存降级）
│   └── RedisConfig                # Redis 配置
├── controller/                     # 控制器层
│   ├── ws/                        # WebSocket 控制器
│   │   └── ChatWsController      # STOMP 消息处理
│   └── http/                      # REST API 控制器
│       ├── ChatRestController     # 基础 REST 接口
│       ├── ChatHistoryController  # 历史消息查询
│       ├── ChatSessionController  # 会话管理
│       └── NotificationInternalController  # 内部通知推送
├── service/                        # 服务层
│   ├── ChatMessagingService       # 消息发送服务接口
│   ├── ChatHistoryService         # 历史消息服务接口
│   ├── ChatSessionService         # 会话服务接口
│   ├── NotificationPushService   # 通知推送服务接口
│   ├── UserProfileCacheService    # 用户信息缓存服务
│   └── impl/                      # 实现类
│       ├── ChatMessagingServiceImpl
│       ├── ChatHistoryServiceImpl
│       ├── ChatSessionServiceImpl
│       └── NotificationPushServiceImpl
├── domain/                         # 领域层
│   ├── entity/                    # 实体类
│   │   ├── ChatSession           # 会话实体
│   │   ├── ChatMessage           # 消息实体
│   │   └── ChatSessionMember     # 会话成员实体
│   └── repository/                # 仓储接口
│       ├── ChatSessionRepository
│       ├── ChatMessageRepository
│       └── ChatSessionMemberRepository
├── infrastructure/                # 基础设施层
│   ├── redis/                     # Redis 实现
│   │   └── RedisConfig
│   ├── client/                    # 外部服务客户端
│   │   ├── SystemUserClient      # Feign Client (system-service)
│   │   ├── SystemUserClientFallback  # 熔断降级
│   │   ├── GameRoomClient        # Feign Client (game-service)
│   │   └── GameRoomClientFallback    # 熔断降级
│   └── kafka/                     # Kafka 事件处理
│       └── RoomEventConsumer     # 房间事件消费者
├── ws/                            # WebSocket 管理
│   ├── WebSocketSessionManager   # 会话管理器
│   ├── WebSocketDisconnectHelper # 断连工具
│   └── SessionInvalidatedListener # 会话失效监听器
└── common/                        # 通用组件
    └── dto/                       # DTO
        ├── ChatMessagePayload
        └── NotificationMessagePayload
```

### 2.2 关键类职责

| 类 | 职责 |
|---|------|
| `ChatServiceApplication` | Spring Boot 启动入口，启用 Feign、JPA |
| `WebSocketStompConfig` | 配置 WebSocket STOMP 端点、消息代理、心跳 |
| `WebSocketAuthChannelInterceptor` | WebSocket 连接鉴权，提取 JWT Token |
| `ChatWsController` | 处理 WebSocket STOMP 消息（发送大厅/房间/私聊消息） |
| `ChatMessagingServiceImpl` | 核心业务逻辑（发送消息、权限校验、消息广播） |
| `ChatSessionServiceImpl` | 会话管理（创建/查询会话、管理成员、未读计数） |
| `ChatHistoryServiceImpl` | 历史消息管理（Redis 缓存 + PostgreSQL 持久化） |
| `UserProfileCacheService` | 用户信息缓存（Redis，TTL 2小时） |
| `SystemUserClient` | 调用 system-service 获取用户信息、禁言状态、好友关系 |
| `GameRoomClient` | 调用 game-service 查询房间成员（可选） |
| `WebSocketSessionManager` | 管理 WebSocket 会话，支持"后连踢前" |
| `WebSocketTokenStore` | 存储 JWT Token（Redis + 内存降级） |

---

## 三、配置说明

### 3.1 WebSocket 配置

**配置文件**：`WebSocketStompConfig.java`

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {
    
    // 注册 STOMP 端点
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // 支持 SockJS 降级
    }
    
    // 配置消息代理
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 简单消息代理（内存模式，支持 /topic 和 /queue）
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{5000, 5000})  // 客户端和服务端每 5 秒心跳
                .setTaskScheduler(wsHeartbeatTaskScheduler());
        
        // 应用目标前缀（客户端发送消息到 /app/xxx）
        registry.setApplicationDestinationPrefixes("/app");
        
        // 用户目标前缀（点对点消息 /user/{userId}/queue/xxx）
        registry.setUserDestinationPrefix("/user");
    }
}
```

**关键配置说明**：

1. **端点路径**：`/ws`（通过 Gateway 路由：`ws://gateway:8080/chat-service/ws`）
2. **消息代理**：使用 Spring 内置的 SimpleBroker（内存模式），适合单实例或小规模部署
3. **心跳机制**：每 5 秒发送一次心跳，保持连接活跃
4. **目标前缀**：
   - `/app`：客户端发送消息的前缀（如 `/app/chat.lobby.send`）
   - `/topic`：广播消息的前缀（如 `/topic/chat.lobby`）
   - `/queue`：点对点消息的前缀（如 `/user/{userId}/queue/chat.private`）

### 3.2 鉴权配置

#### 3.2.1 Spring Security 配置

**配置文件**：`SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/ws/**").authenticated()  // WebSocket 需要鉴权
                .requestMatchers("/api/**").authenticated()  // REST API 需要鉴权
                .anyRequest().authenticated()
        );
        
        // OAuth2 Resource Server (JWT)
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        
        return http.build();
    }
}
```

**配置说明**：

- **OAuth2 Resource Server**：使用 Keycloak 作为 JWT 发行者
- **JWT 验证**：通过 `issuer-uri` 配置 Keycloak 地址，自动验证 JWT Token
- **无状态会话**：使用 `STATELESS` 模式，不创建 HTTP Session

#### 3.2.2 WebSocket 鉴权拦截器

**配置文件**：`WebSocketAuthChannelInterceptor.java`

**核心逻辑**：

1. **CONNECT 阶段**：
   - 从 STOMP header 提取 `Authorization: Bearer {token}` 或 `access_token`
   - 如果 header 中没有，尝试从 HTTP 请求头获取（Gateway 过滤器放入的）
   - 使用 `JwtDecoder` 解码 Token，验证签名和过期时间
   - 创建 `JwtAuthenticationToken`，设置到 `StompHeaderAccessor`
   - **从 JWT 中提取 `sid`（loginSessionId）**：优先使用 `sid` claim，如果没有则使用 `session_state` claim（向后兼容）
   - **保存原始 Token 到 `WebSocketTokenStore`**：
     - **主要 key**：`loginSessionId`（sid）- 因为 `sid` 在整个登录生命周期内不变，即使 token 刷新也不会变
     - **备用 key**：`wsSessionId`（WebSocket sessionId）- 向后兼容，当 `loginSessionId` 为 null 时使用
     - 同时保存两个 key，确保无论使用哪个 key 都能获取到 token
   - 设置 `SecurityContext` 和 `JwtTokenHolder`（ThreadLocal）

2. **非 CONNECT 消息**（如 SEND）：
   - 从已建立的会话中获取用户身份（`accessor.getUser()`）
   - **优先从 `accessor.getUser()` 中提取 `loginSessionId`（sid）**
   - **优先使用 `loginSessionId` 从 `WebSocketTokenStore` 获取 token**（因为 token 刷新时 sid 不变）
   - **降级策略**：如果使用 `loginSessionId` 获取失败，尝试使用 `wsSessionId` 获取
   - **自动修复**：如果从 `wsSessionId` 获取到 token，尝试从 token 中提取 `loginSessionId` 并更新存储（确保后续查询能使用 `loginSessionId`）
   - 如果 `accessor.getUser()` 为 null 但 token 存在，尝试从 token 恢复用户信息
   - 设置 `JwtTokenHolder`（ThreadLocal）和 `SecurityContext`

**为什么需要保存 Token？**

- WebSocket 连接建立后，后续的 SEND 消息不会携带 Token
- 但在处理消息时，可能需要调用 Feign Client（如验证好友关系）
- Feign Client 需要 Token 才能通过 Gateway 的鉴权
- 因此需要在 CONNECT 时保存 Token，后续消息处理时恢复

**为什么使用 `sid`（loginSessionId）作为主要 key？**

- **Token 刷新问题**：JWT access_token 会定期刷新（通常每 5 分钟），但 `sid`（Session ID）在整个登录生命周期内保持不变
- **Key 稳定性**：使用 `sid` 作为 key，即使 token 刷新，也能用同一个 key 覆盖旧的 token，而不是创建新的 key
- **向后兼容**：如果 `sid` 不存在（旧版本 JWT），降级使用 `wsSessionId` 作为 key

**Token 存储策略**：

- **Redis 存储**（多实例支持）：使用 `session-common` 提供的 `sessionRedisTemplate`（连接到 Redis db0）
- **内存降级**（单实例）：如果 Redis 不可用，降级到 `ConcurrentHashMap`
- **TTL**：1 小时（与 JWT Token 过期时间一致）
- **Key 格式**：`ws:token:{loginSessionId}` 或 `ws:token:{wsSessionId}`

### 3.3 Redis 配置

**配置文件**：`application.yml`

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:zaqxsw}
      database: ${REDIS_DATABASE:4}  # chat-service 专用 DB
      timeout: 2s
      lettuce:
        pool:
          max-active: 32
          max-idle: 16
          min-idle: 0
          max-wait: 5s
```

**Redis 用途**：

1. **消息历史缓存**（chat-service 专用 DB，database: 4）：
   - 房间消息：`chat:room:history:{roomId}` (List)
   - 私聊消息：`chat:private:history:{sessionId}` (List)
   - TTL：24 小时

2. **用户信息缓存**（session-common Redis，database: 0）：
   - 用户档案：`user:profile:{userId}` (String, JSON)
   - TTL：2 小时

3. **Token 存储**（session-common Redis，database: 0）：
   - WebSocket Token：`ws:token:{loginSessionId}` 或 `ws:token:{wsSessionId}` (String)
   - **主要 key**：`ws:token:{loginSessionId}` - 使用 JWT 的 `sid` claim（登录会话ID），在整个登录生命周期内不变
   - **备用 key**：`ws:token:{wsSessionId}` - 使用 WebSocket sessionId，向后兼容
   - **存储策略**：同时保存两个 key，确保无论使用哪个 key 都能获取到 token
   - TTL：1 小时（与 JWT Token 过期时间一致）

**Redis 序列化配置**：

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);
        
        // Key 使用 String 序列化
        StringRedisSerializer keySer = new StringRedisSerializer();
        // Value 使用 JSON 序列化
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer();
        
        tpl.setKeySerializer(keySer);
        tpl.setValueSerializer(valSer);
        tpl.setHashKeySerializer(keySer);
        tpl.setHashValueSerializer(valSer);
        
        return tpl;
    }
}
```

---

## 四、WebSocket 实现

### 4.1 全局 WebSocket 连接

**连接流程**：

1. **前端连接**：
   ```javascript
   const socket = new SockJS('http://gateway:8080/chat-service/ws?access_token=xxx');
   const stompClient = Stomp.over(socket);
   ```

2. **Gateway 路由**：
   - Gateway 接收 WebSocket 连接请求
   - 验证 JWT Token（通过 OAuth2 过滤器）
   - 转发到 `chat-service:8083/ws`

3. **chat-service 处理**：
   - `WebSocketAuthChannelInterceptor` 拦截 CONNECT 消息
   - 提取 Token，验证并设置用户身份
   - `WebSocketSessionManager` 注册会话（支持"后连踢前"）

**会话管理**：

- **注册**：连接建立时，注册到 `SessionRegistry`（按 `loginSessionId + service` 维度）
- **踢人**：如果同一用户在同一服务有旧连接，先踢掉旧连接
- **清理**：连接断开时，从 `SessionRegistry` 注销，清理 Token 存储

### 4.2 WebSocket 订阅

#### 4.2.1 订阅列表

| 订阅路径 | 说明 | 消息类型 |
|---------|------|---------|
| `/topic/chat.lobby` | 大厅聊天（全局广播） | `ChatMessagePayload` |
| `/topic/chat.room.{roomId}` | 房间聊天（房间内广播） | `ChatMessagePayload` |
| `/user/{userId}/queue/chat.private` | 私聊消息（点对点） | `ChatMessagePayload` |
| `/user/{userId}/queue/notify` | 系统通知（点对点） | `NotificationMessagePayload` |
| `/user/{userId}/queue/system.kick` | 踢人通知（点对点） | `{type: "WS_KICK", reason: string}` |

#### 4.2.2 前端订阅示例

```javascript
// 订阅大厅聊天
stompClient.subscribe('/topic/chat.lobby', (message) => {
    const msg = JSON.parse(message.body);
    // { type: "LOBBY", senderId, senderName, content, timestamp }
});

// 订阅房间聊天
stompClient.subscribe(`/topic/chat.room.${roomId}`, (message) => {
    const msg = JSON.parse(message.body);
    // { type: "ROOM", roomId, senderId, senderName, content, timestamp }
});

// 订阅私聊消息
stompClient.subscribe(`/user/${userId}/queue/chat.private`, (message) => {
    const msg = JSON.parse(message.body);
    // { type: "PRIVATE", senderId, targetUserId, content, timestamp }
});

// 订阅系统通知
stompClient.subscribe(`/user/${userId}/queue/notify`, (message) => {
    const notification = JSON.parse(message.body);
    // { type, title, content, fromUserId, payload, actions }
});
```

### 4.3 WebSocket 消息发送

#### 4.3.1 客户端发送（`/app/*`）

| 发送路径 | 说明 | 请求体 |
|---------|------|--------|
| `/app/chat.lobby.send` | 发送大厅消息 | `{ content: string, clientOpId?: string }` |
| `/app/chat.room.send` | 发送房间消息 | `{ roomId: string, content: string, clientOpId?: string }` |
| `/app/chat.private.send` | 发送私聊消息 | `{ targetUserId: string, content: string, clientOpId?: string }` |

**前端发送示例**：

```javascript
// 发送大厅消息
stompClient.send('/app/chat.lobby.send', {}, JSON.stringify({
    content: 'Hello, world!',
    clientOpId: 'uuid-xxx'  // 幂等键（可选）
}));

// 发送房间消息
stompClient.send('/app/chat.room.send', {}, JSON.stringify({
    roomId: 'room-123',
    content: 'Nice move!',
    clientOpId: 'uuid-xxx'
}));

// 发送私聊消息
stompClient.send('/app/chat.private.send', {}, JSON.stringify({
    targetUserId: 'user-uuid',
    content: 'Hello!',
    clientOpId: 'uuid-xxx'
}));
```

#### 4.3.2 服务端处理

**控制器**：`ChatWsController.java`

```java
@Controller
public class ChatWsController {
    
    @MessageMapping("/chat.lobby.send")
    public void sendLobby(@Payload SendMessage cmd, SimpMessageHeaderAccessor sha) {
        // 从 SecurityContext 获取用户身份
        String userId = sha.getUser().getName();
        chatMessagingService.sendLobbyMessage(userId, cmd.getContent());
    }
    
    @MessageMapping("/chat.room.send")
    public void sendRoom(@Payload SendMessage cmd, SimpMessageHeaderAccessor sha) {
        String userId = sha.getUser().getName();
        chatMessagingService.sendRoomMessage(userId, cmd.getRoomId(), cmd.getContent());
    }
    
    @MessageMapping("/chat.private.send")
    public void sendPrivate(@Payload SendPrivateMessage cmd, SimpMessageHeaderAccessor sha) {
        String userId = sha.getUser().getName();
        
        // 兜底方案：如果 ThreadLocal 中没有 token，从 Redis 中获取并设置
        // 关键修复：优先使用 loginSessionId（sid）获取 token，因为 token 刷新时 sid 不变
        String token = JwtTokenHolder.getToken();
        if (token == null || token.isBlank()) {
            // 优先从 JWT 中提取 loginSessionId（sid）
            String loginSessionId = null;
            if (sha.getUser() instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                Object sidObj = jwt.getClaim("sid");
                if (sidObj != null) {
                    loginSessionId = sidObj.toString();
                }
                if (loginSessionId == null || loginSessionId.isBlank()) {
                    Object sessionStateObj = jwt.getClaim("session_state");
                    if (sessionStateObj != null) {
                        loginSessionId = sessionStateObj.toString();
                    }
                }
            }
            
            // 优先使用 loginSessionId 获取 token
            if (loginSessionId != null && !loginSessionId.isBlank()) {
                token = tokenStore.getToken(loginSessionId);
                if (token != null && !token.isBlank()) {
                    JwtTokenHolder.setToken(token);
                }
            }
            
            // 降级：如果使用 loginSessionId 获取失败，尝试使用 WebSocket sessionId
            if ((token == null || token.isBlank())) {
                String wsSessionId = sha.getSessionId();
                if (wsSessionId != null) {
                    token = tokenStore.getToken(wsSessionId);
                    if (token != null && !token.isBlank()) {
                        JwtTokenHolder.setToken(token);
                    }
                }
            }
        }
        
        chatMessagingService.sendPrivateMessage(
            userId, 
            cmd.getTargetUserId(), 
            cmd.getContent(),
            cmd.getClientOpId()
        );
    }
}
```

### 4.4 WebSocket 订阅和前端调用关系

**流程图**：

```
前端连接 WebSocket
  ↓
订阅 /topic/chat.lobby（大厅）
订阅 /topic/chat.room.{roomId}（房间）
订阅 /user/{userId}/queue/chat.private（私聊）
订阅 /user/{userId}/queue/notify（通知）
  ↓
用户发送消息
  ↓
前端调用 /app/chat.*.send
  ↓
chat-service 处理消息
  ↓
服务端广播到对应订阅
  ↓
前端接收消息并显示
```

**关键点**：

1. **订阅是持久的**：连接建立后，订阅一直有效，直到取消订阅或断开连接
2. **消息路由**：
   - `/topic/*`：广播消息，所有订阅者都能收到
   - `/user/{userId}/queue/*`：点对点消息，只有目标用户能收到
3. **Spring WebSocket 自动路由**：
   - `/user/{userId}/queue/chat.private` 会被 Spring 自动路由到对应用户的 WebSocket 连接
   - 前提是用户已建立 WebSocket 连接并订阅了该队列

---

## 五、OpenFeign 配置与使用

### 5.1 Feign 配置

#### 5.1.1 依赖配置

**pom.xml**：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

#### 5.1.2 启用 Feign

**启动类**：`ChatServiceApplication.java`

```java
@SpringBootApplication
@EnableFeignClients  // 启用 Feign Client
public class ChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
```

#### 5.1.3 服务发现配置

**application.yml**：

```yaml
spring:
  cloud:
    loadbalancer:
      enabled: true
    discovery:
      client:
        simple:
          instances:
            system-service:
              - uri: http://127.0.0.1:8082
            game-service:
              - uri: http://127.0.0.1:8081
```

**说明**：

- **本地开发**：使用 `SimpleDiscoveryClient` 配置服务实例地址
- **Docker Compose**：容器 DNS 自动解析服务名（无需配置）
- **Kubernetes**：K8s Service + DNS 自动解析服务名（无需配置）

#### 5.1.4 熔断器配置

**application.yml**：

```yaml
feign:
  circuitbreaker:
    enabled: true  # 启用熔断

resilience4j:
  circuitbreaker:
    instances:
      systemUserClient:  # 对应 @CircuitBreaker(name = "systemUserClient")
        slidingWindowSize: 20  # 滑动窗口大小
        failureRateThreshold: 50  # 失败率阈值（50%）
        waitDurationInOpenState: 10s  # 熔断后等待时间
        permittedNumberOfCallsInHalfOpenState: 3  # 半开状态试探调用次数
      gameRoomClient:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
```

### 5.2 Feign Client 定义

#### 5.2.1 SystemUserClient

**接口定义**：`SystemUserClient.java`

```java
@FeignClient(name = "system-service", fallback = SystemUserClientFallback.class)
public interface SystemUserClient {
    
    @GetMapping("/api/users/{userId}/info")
    @CircuitBreaker(name = "systemUserClient")
    UserInfo getUserInfo(@PathVariable String userId);
    
    @GetMapping("/api/users/{userId}/muted")
    @CircuitBreaker(name = "systemUserClient")
    boolean isMuted(@PathVariable String userId);
    
    @GetMapping("/api/friends/check/{userId1}/{userId2}")
    @CircuitBreaker(name = "systemUserClient")
    ApiResponse<Boolean> isFriend(@PathVariable String userId1, @PathVariable String userId2);
    
    record UserInfo(String userId, String username, String nickname, String avatarUrl, String email) {}
}
```

**降级实现**：`SystemUserClientFallback.java`

```java
@Component
public class SystemUserClientFallback implements SystemUserClient {
    
    @Override
    public UserInfo getUserInfo(String userId) {
        log.warn("system-service unavailable, return minimal user info, userId={}", userId);
        return new UserInfo(userId, userId, userId, null, null);
    }
    
    @Override
    public boolean isMuted(String userId) {
        log.warn("system-service unavailable, default muted=false, userId={}", userId);
        return false;
    }
    
    @Override
    public ApiResponse<Boolean> isFriend(String userId1, String userId2) {
        log.warn("system-service unavailable, default isFriend=true (degradation)");
        // 降级策略：为了可用性允许发送（生产环境建议改为 false）
        return ApiResponse.success(true);
    }
}
```

#### 5.2.2 GameRoomClient

**接口定义**：`GameRoomClient.java`

```java
@FeignClient(name = "game-service", fallback = GameRoomClientFallback.class)
public interface GameRoomClient {
    
    @GetMapping("/api/game/rooms/{roomId}/members")
    @CircuitBreaker(name = "gameRoomClient")
    List<String> getRoomMembers(@PathVariable String roomId);
}
```

**降级实现**：`GameRoomClientFallback.java`

```java
@Component
public class GameRoomClientFallback implements GameRoomClient {
    
    @Override
    public List<String> getRoomMembers(String roomId) {
        log.warn("game-service unavailable, return empty members, roomId={}", roomId);
        return Collections.emptyList();
    }
}
```

### 5.3 Feign 调用中的 Token 传递

#### 5.3.1 问题背景

**为什么需要传递 Token？**

- chat-service 作为资源服务器，接收的请求已经通过 Gateway 鉴权
- 但 chat-service 调用其他服务（如 system-service）时，需要携带 Token
- Gateway 需要验证 Token 才能放行请求

**WebSocket 场景的特殊性**：

- WebSocket 连接建立后，后续的 SEND 消息不会携带 Token
- 但在处理消息时，可能需要调用 Feign Client（如验证好友关系）
- WebSocket 消息处理是异步的，ThreadLocal 可能在不同线程间丢失
- 因此需要在 CONNECT 时保存 Token 到 Redis，后续消息处理时恢复

#### 5.3.2 解决方案架构

**核心组件**：

1. **`JwtTokenHolder`（ThreadLocal）**：`web-common` 库提供的 ThreadLocal 存储，用于临时存储当前线程的 Token
2. **`WebSocketTokenStore`（Redis + 内存降级）**：持久化存储 Token，支持多实例部署
3. **`FeignAuthAutoConfiguration`（Feign 拦截器）**：自动从 ThreadLocal 获取 Token，添加到 Feign 请求的 Authorization header

**Token 传递流程**：

```
┌─────────────────────────────────────────────────────────────────┐
│  WebSocket CONNECT 阶段                                          │
├─────────────────────────────────────────────────────────────────┤
│  1. 前端发送 CONNECT 消息（携带 Token）                           │
│     ↓                                                            │
│  2. WebSocketAuthChannelInterceptor.preSend() 拦截             │
│     - 从 STOMP header 提取 Token                                 │
│     - 解码 JWT，提取 sid（loginSessionId）                        │
│     ↓                                                            │
│  3. 保存 Token 到 WebSocketTokenStore                            │
│     - 主要 key: ws:token:{loginSessionId}  ← 使用 sid           │
│     - 备用 key: ws:token:{wsSessionId}    ← 使用 WebSocket ID   │
│     - 同时写入 Redis 和内存存储（双重保障）                       │
│     ↓                                                            │
│  4. 设置到 ThreadLocal                                           │
│     - JwtTokenHolder.setToken(token)                            │
│     - SecurityContextHolder.setContext(...)                     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  WebSocket SEND 消息阶段（如发送私聊消息）                        │
├─────────────────────────────────────────────────────────────────┤
│  1. 前端发送 SEND 消息（/app/chat.private.send）                 │
│     ↓                                                            │
│  2. WebSocketAuthChannelInterceptor.preSend() 拦截（非 CONNECT） │
│     - 从 accessor.getUser() 提取 loginSessionId（sid）            │
│     ↓                                                            │
│  3. 优先使用 loginSessionId 从 Redis 获取 Token                 │
│     - token = tokenStore.getToken(loginSessionId)                │
│     - 如果成功，设置到 ThreadLocal: JwtTokenHolder.setToken()    │
│     ↓                                                            │
│  4. 降级：如果 loginSessionId 获取失败，使用 wsSessionId         │
│     - token = tokenStore.getToken(wsSessionId)                   │
│     - 如果成功，设置到 ThreadLocal                               │
│     - 自动修复：从 token 提取 loginSessionId，更新存储            │
│     ↓                                                            │
│  5. 如果 accessor.getUser() 为 null，从 token 恢复用户信息        │
│     - 解码 token，创建 JwtAuthenticationToken                     │
│     - 设置到 SecurityContext                                    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  ChatWsController.sendPrivate() 兜底逻辑                         │
├─────────────────────────────────────────────────────────────────┤
│  1. 检查 ThreadLocal 中是否有 Token                              │
│     - token = JwtTokenHolder.getToken()                         │
│     ↓                                                            │
│  2. 如果 Token 为空，从 Redis 恢复                                │
│     - 优先使用 loginSessionId（sid）获取                          │
│     - 降级使用 wsSessionId 获取                                  │
│     - 设置到 ThreadLocal                                        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  ChatMessagingServiceImpl.sendPrivateMessage() 业务逻辑           │
├─────────────────────────────────────────────────────────────────┤
│  1. 调用 Feign Client（验证好友关系）                             │
│     - systemUserClient.isFriend(senderId, targetUserId)          │
│     ↓                                                            │
│  2. FeignAuthAutoConfiguration 拦截器自动执行                    │
│     - 从 ThreadLocal 获取 Token: JwtTokenHolder.getToken()      │
│     - 如果 ThreadLocal 中没有，尝试从 SecurityContext 获取       │
│     - 添加到 Feign 请求 Header: Authorization: Bearer {token}     │
│     ↓                                                            │
│  3. Feign 请求发送到 system-service                              │
│     - Gateway 验证 Token，放行请求                                │
│     - system-service 处理请求并返回结果                          │
└─────────────────────────────────────────────────────────────────┘
```

#### 5.3.3 详细实现说明

**1. CONNECT 阶段的 Token 存储**

```java
// WebSocketAuthChannelInterceptor.preSend()
if (StompCommand.CONNECT.equals(accessor.getCommand())) {
    // 1. 提取 Token
    String token = extractTokenFromHeader(accessor);
    
    // 2. 解码 JWT，提取 sid（loginSessionId）
    Jwt jwt = jwtDecoder.decode(token);
    String loginSessionId = extractLoginSessionId(jwt);  // 优先使用 sid，降级使用 session_state
    String wsSessionId = accessor.getSessionId();
    
    // 3. 保存 Token 到 Redis（双重 key 策略）
    if (loginSessionId != null && !loginSessionId.isBlank()) {
        tokenStore.putToken(loginSessionId, token);  // 主要 key
    }
    if (wsSessionId != null) {
        tokenStore.putToken(wsSessionId, token);      // 备用 key
    }
    
    // 4. 设置到 ThreadLocal
    JwtTokenHolder.setToken(token);
    SecurityContextHolder.setContext(securityContext);
}
```

**2. SEND 消息阶段的 Token 恢复**

```java
// WebSocketAuthChannelInterceptor.preSend()（非 CONNECT）
else {
    // 1. 获取 WebSocket sessionId
    String wsSessionId = accessor.getSessionId();
    
    // 2. 优先从 accessor.getUser() 提取 loginSessionId（sid）
    String loginSessionId = null;
    if (accessor.getUser() instanceof JwtAuthenticationToken jwtAuth) {
        Jwt jwt = jwtAuth.getToken();
        loginSessionId = extractLoginSessionId(jwt);
    }
    
    // 3. 优先使用 loginSessionId 获取 Token
    String token = null;
    if (loginSessionId != null && !loginSessionId.isBlank()) {
        token = tokenStore.getToken(loginSessionId);
        if (token != null && !token.isBlank()) {
            JwtTokenHolder.setToken(token);
        }
    }
    
    // 4. 降级：使用 wsSessionId 获取 Token
    if ((token == null || token.isBlank()) && wsSessionId != null) {
        token = tokenStore.getToken(wsSessionId);
        if (token != null && !token.isBlank()) {
            JwtTokenHolder.setToken(token);
            
            // 5. 自动修复：从 token 提取 loginSessionId 并更新存储
            try {
                Jwt jwt = jwtDecoder.decode(token);
                String extractedLoginSessionId = extractLoginSessionId(jwt);
                if (extractedLoginSessionId != null && !extractedLoginSessionId.isBlank()) {
                    tokenStore.putToken(extractedLoginSessionId, token);
                }
            } catch (Exception e) {
                // 忽略提取失败
            }
        }
    }
    
    // 6. 如果 accessor.getUser() 为 null，从 token 恢复用户信息
    if (token != null && !token.isBlank() && accessor.getUser() == null) {
        Jwt jwt = jwtDecoder.decode(token);
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, ...);
        accessor.setUser(authentication);
        SecurityContextHolder.setContext(new SecurityContextImpl(authentication));
    }
}
```

**3. Feign 拦截器自动添加 Token**

```java
// FeignAuthAutoConfiguration（web-common）
@Bean
public RequestInterceptor feignRequestInterceptor() {
    return requestTemplate -> {
        String authorization = null;
        
        // 方式1：从 HTTP 请求 Header 中获取（适用于 HTTP 请求场景）
        ServletRequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            authorization = request.getHeader("Authorization");
        }
        
        // 方式2：从 ThreadLocal 获取（适用于 WebSocket 消息处理场景）
        if (authorization == null || authorization.isBlank()) {
            String token = JwtTokenHolder.getToken();
            if (token != null && !token.isBlank()) {
                authorization = "Bearer " + token;
            }
        }
        
        // 方式3：从 SecurityContext 中获取（备用方案）
        if (authorization == null || authorization.isBlank()) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                String tokenValue = jwt.getTokenValue();
                if (tokenValue != null && !tokenValue.isBlank()) {
                    authorization = "Bearer " + tokenValue;
                }
            }
        }
        
        // 添加到 Feign 请求 Header
        if (authorization != null && !authorization.isBlank()) {
            requestTemplate.header("Authorization", authorization);
        }
    };
}
```

#### 5.3.4 为什么使用 `sid`（loginSessionId）作为主要 key？

**核心原因**：

1. **Token 刷新问题**：
   - JWT access_token 会定期刷新（通常每 5 分钟），刷新后 token 内容会变化
   - 但 `sid`（Session ID）在整个登录生命周期内保持不变
   - 使用 `sid` 作为 key，即使 token 刷新，也能用同一个 key 覆盖旧的 token

2. **Key 稳定性**：
   - 如果使用 `wsSessionId` 作为 key，每次 WebSocket 重连都会创建新的 key
   - 使用 `sid` 作为 key，即使 WebSocket 重连，也能复用同一个 key

3. **向后兼容**：
   - 如果 JWT 中没有 `sid` claim（旧版本），降级使用 `wsSessionId` 作为 key
   - 同时保存两个 key，确保无论使用哪个 key 都能获取到 token

**示例场景**：

```
时间线：
T1: 用户登录，获取 access_token（sid: "abc123"）
T2: WebSocket CONNECT，保存 token 到 ws:token:abc123
T3: Token 刷新，新的 access_token（sid: "abc123"，不变）
T4: WebSocket SEND 消息，使用 sid "abc123" 获取 token（获取到最新的 token）
```

如果使用 `wsSessionId` 作为 key：
```
T2: 保存 token 到 ws:token:ws-session-001
T3: Token 刷新，但 ws-session-001 的 token 还是旧的
T4: 使用 ws-session-001 获取 token（获取到旧的 token，可能导致 401）
```

#### 5.3.5 为什么选择这种方式？

**优点**：

1. **透明性**：业务代码无需关心 Token 传递，Feign 拦截器自动处理
2. **兼容性**：同时支持 REST API 和 WebSocket 场景
3. **可靠性**：
   - Redis 存储支持多实例部署
   - 内存降级支持单实例
   - 双重 key 策略确保 token 可获取
4. **自动修复**：如果从 `wsSessionId` 获取到 token，自动提取 `loginSessionId` 并更新存储

**注意事项**：

1. **ThreadLocal 清理**：
   - 不要在拦截器中清理 ThreadLocal（Feign 调用可能还在进行中）
   - ThreadLocal 会在下一个消息处理时被覆盖，或在连接断开时清理

2. **Token 过期**：
   - Token TTL 为 1 小时，与 JWT Token 过期时间一致
   - 如果 Token 过期，Feign 调用会失败，需要前端重新连接

3. **多实例部署**：
   - 必须使用 Redis 存储 Token（不能使用内存存储）
   - 确保所有实例连接到同一个 Redis db0

4. **Token 刷新**：
   - 当 access_token 刷新时，需要更新 Redis 中的 token
   - 目前实现：如果从 `wsSessionId` 获取到 token，会自动提取 `loginSessionId` 并更新存储
   - 建议：前端在 token 刷新后重新建立 WebSocket 连接，确保使用最新的 token

### 5.4 Feign 调用场景

#### 5.4.1 获取用户信息

**调用时机**：
- 发送消息时，需要获取发送者的昵称、头像
- 查询会话列表时，需要获取对方用户信息

**实现**：`UserProfileCacheService`

```java
@Service
public class UserProfileCacheService {
    
    // 优先从缓存获取，缓存未命中时调用 Feign
    public Optional<UserProfileView> get(String userId) {
        // 1. 从 Redis 缓存获取（TTL 2小时）
        String json = sessionRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (json != null) {
            return Optional.of(objectMapper.readValue(json, UserProfileView.class));
        }
        
        // 2. 缓存未命中，调用 Feign（这里需要 Token）
        SystemUserClient.UserInfo userInfo = systemUserClient.getUserInfo(userId);
        if (userInfo != null) {
            UserProfileView view = new UserProfileView(...);
            // 3. 写入缓存
            put(view);
            return Optional.of(view);
        }
        
        return Optional.empty();
    }
}
```

#### 5.4.2 验证好友关系

**调用时机**：
- 发送私聊消息时，验证双方是否是好友

**实现**：`ChatMessagingServiceImpl.sendPrivateMessage()`

```java
// 验证好友关系：只有好友之间才能发送私聊消息
try {
    ApiResponse<Boolean> response = systemUserClient.isFriend(senderId, targetUserId);
    if (response == null || response.code() != 200 || !response.data()) {
        log.warn("私聊消息发送失败：不是好友关系");
        return false;
    }
} catch (Exception e) {
    log.error("验证好友关系失败", e);
    // 降级策略：为了可用性允许发送（生产环境建议改为 return false）
    log.warn("好友关系验证失败，但允许消息发送（降级策略）");
}
```

#### 5.4.3 查询房间成员

**调用时机**：
- 发送房间消息时，验证用户是否在房间内（可选）

**实现**：`GameRoomClient.getRoomMembers()`

```java
// 可选：查询房间成员，用于权限校验
List<String> members = gameRoomClient.getRoomMembers(roomId);
if (members != null && !members.contains(userId)) {
    log.warn("用户不在房间内，拒绝发送消息");
    return;
}
```

**注意**：此功能目前未使用，因为房间成员信息通过 Kafka 事件同步（见下文）

---

## 六、房间会话实现

### 6.1 房间会话特点

- **按需创建**：第一次发送消息时创建，而不是房间创建时创建
- **临时性**：房间删除后，聊天记录会被清理（通过 Kafka 事件）
- **成员动态**：房间成员变化时，通过 Kafka 事件同步（暂未实现）

### 6.2 房间消息流程

**流程图**：

```
用户发送房间消息
  ↓
ChatWsController.sendRoom()
  ↓
ChatMessagingServiceImpl.sendRoomMessage()
  ↓
1. 构建消息载荷（ChatMessagePayload）
2. 广播到 /topic/chat.room.{roomId}
3. 存储到 Redis（chat:room:history:{roomId}）
  ↓
订阅者接收消息
```

**实现代码**：

```java
@Override
public void sendRoomMessage(String userId, String roomId, String content) {
    // 1. 内容清洗
    String body = sanitize(content);
    if (body == null) {
        return;
    }
    
    // 2. 构建消息载荷
    ChatMessagePayload payload = ChatMessagePayload.builder()
            .type("ROOM")
            .roomId(roomId)
            .senderId(userId)
            .senderName(resolveDisplayName(userId))  // 从缓存获取用户信息
            .content(body)
            .timestamp(Instant.now().toEpochMilli())
            .build();
    
    // 3. 推送实时消息（广播到所有订阅者）
    messagingTemplate.convertAndSend("/topic/chat.room." + roomId, payload);
    
    // 4. 记录房间历史（Redis，保留最近 50 条）
    chatHistoryService.appendRoomMessage(payload, 50);
}
```

### 6.3 房间历史消息

**Redis 存储**：

- **Key**：`chat:room:history:{roomId}`
- **类型**：List（按时间顺序）
- **TTL**：24 小时
- **最大长度**：50 条（自动截断）

**查询实现**：`ChatHistoryServiceImpl.listRoomMessages()`

```java
@Override
public List<ChatMessagePayload> listRoomMessages(String roomId, int limit) {
    String key = ROOM_KEY_PREFIX + roomId;
    int fetch = limit > 0 ? limit : DEFAULT_MAX_SIZE;
    
    // 从 Redis List 读取（从后往前，取最近 N 条）
    Long size = stringRedisTemplate.opsForList().size(key);
    if (size == null || size == 0) {
        return Collections.emptyList();
    }
    
    long start = Math.max(-fetch, -size);
    List<String> raw = stringRedisTemplate.opsForList().range(key, start, -1);
    
    // 反序列化为 ChatMessagePayload
    return raw.stream()
            .map(json -> objectMapper.readValue(json, ChatMessagePayload.class))
            .collect(Collectors.toList());
}
```

### 6.4 房间事件处理

**Kafka 消费者**：`RoomEventConsumer.java`

```java
@Component
@KafkaListener(topics = "${chat.room-events-topic:room-events}", groupId = "chat-service-room-events")
public void onRoomEvent(String payload) {
    JsonNode node = objectMapper.readTree(payload);
    String type = node.path("eventType").asText();
    String roomId = node.path("roomId").asText();
    
    if (type.contains("DESTROY") || type.contains("DELETE")) {
        // 房间删除时，清理聊天记录
        chatHistoryService.deleteRoomMessages(roomId);
    }
}
```

**注意**：此功能目前被禁用（`@Component` 注释），等待 game-service 实现房间事件发布

---

## 七、全局消息通知能力

### 7.1 通知推送服务

**服务接口**：`NotificationPushService`

```java
public interface NotificationPushService {
    void sendToUser(String userId, NotificationMessagePayload payload);
}
```

**实现**：`NotificationPushServiceImpl`

```java
@Service
public class NotificationPushServiceImpl implements NotificationPushService {
    
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public void sendToUser(String userId, NotificationMessagePayload payload) {
        // 使用点对点消息推送
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/notify",  // 通知队列
            payload
        );
    }
}
```

### 7.2 内部通知接口

**控制器**：`NotificationInternalController.java`

```java
@RestController
@RequestMapping("/api/internal/notify")
public class NotificationInternalController {
    
    private final NotificationPushService notificationPushService;
    
    @PostMapping
    public ResponseEntity<Void> push(
            @Valid @RequestBody NotifyRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        NotificationMessagePayload payload = NotificationMessagePayload.builder()
                .type(request.getType())
                .title(request.getTitle())
                .content(request.getContent())
                .fromUserId(request.getFromUserId())
                .payload(request.getPayload())
                .actions(request.getActions())
                .build();
        
        notificationPushService.sendToUser(request.getUserId(), payload);
        return ResponseEntity.accepted().build();
    }
}
```

### 7.3 使用场景

**其他服务调用**（如 system-service）：

```java
@FeignClient(name = "chat-service")
public interface ChatNotificationClient {
    
    @PostMapping("/api/internal/notify")
    void pushNotification(@RequestBody NotifyRequest request);
}

// 使用示例
chatNotificationClient.pushNotification(NotifyRequest.builder()
    .userId("user-uuid")
    .type("FRIEND_REQUEST")
    .title("好友申请")
    .content("用户A申请添加您为好友")
    .build());
```

### 7.4 前端订阅

```javascript
// 订阅系统通知
stompClient.subscribe(`/user/${userId}/queue/notify`, (message) => {
    const notification = JSON.parse(message.body);
    // { type, title, content, fromUserId, payload, actions }
    showNotification(notification);
});
```

---

## 八、私聊消息会话

### 8.1 私聊会话特点

- **持久化存储**：消息保存到 PostgreSQL，支持历史查询
- **按需创建**：第一次发送消息时创建会话
- **唯一性**：相同两个用户只能有一个私聊会话（通过 `support_key` 唯一约束）
- **未读计数**：每个会话独立维护未读消息数

### 8.2 私聊会话创建

**实现**：`ChatSessionServiceImpl.getOrCreatePrivateSession()`

```java
@Transactional
public ChatSession getOrCreatePrivateSession(UUID userId1, UUID userId2) {
    // 1. 生成私聊会话唯一键（按用户ID字典序排序）
    String supportKey = buildPrivateSessionKey(userId1, userId2);
    // 格式：min(user1,user2)|max(user1,user2)
    
    // 2. 查询是否已存在会话
    return sessionRepository.findBySupportKeyAndSessionType(supportKey, SessionType.PRIVATE)
            .orElseGet(() -> {
                // 3. 创建新会话
                ChatSession session = ChatSession.builder()
                        .sessionType(SessionType.PRIVATE)
                        .supportKey(supportKey)
                        .memberCount(2)
                        .build();
                session = sessionRepository.save(session);
                
                // 4. 创建两个成员记录
                ChatSessionMember member1 = ChatSessionMember.builder()
                        .sessionId(session.getId())
                        .userId(userId1)
                        .memberRole(MemberRole.MEMBER)
                        .build();
                ChatSessionMember member2 = ChatSessionMember.builder()
                        .sessionId(session.getId())
                        .userId(userId2)
                        .memberRole(MemberRole.MEMBER)
                        .build();
                
                memberRepository.save(member1);
                memberRepository.save(member2);
                
                return session;
            });
}
```

**关键点**：

1. **唯一键生成**：按用户ID字典序排序，确保无论传入顺序如何，都生成相同的 key
2. **事务保证**：使用 `@Transactional` 确保会话和成员记录的原子性创建
3. **唯一约束**：数据库层面有 `uk_chat_private_key` 唯一约束，防止重复创建

### 8.3 私聊消息发送

**流程**：`ChatMessagingServiceImpl.sendPrivateMessage()`

```java
public boolean sendPrivateMessage(String senderId, String targetUserId, String content, String clientOpId) {
    // 1. 参数校验：不能给自己发消息
    if (senderId.equals(targetUserId)) {
        return false;
    }
    
    // 2. 验证好友关系（Feign 调用 system-service）
    ApiResponse<Boolean> response = systemUserClient.isFriend(senderId, targetUserId);
    if (!response.data()) {
        return false;
    }
    
    // 3. 内容清洗
    String body = sanitize(content);
    
    // 4. 获取或创建私聊会话
    ChatSession session = chatSessionService.getOrCreatePrivateSession(senderUuid, targetUuid);
    
    // 5. 保存消息到数据库（幂等检查）
    ChatMessage savedMessage = chatSessionService.savePrivateMessage(
            session.getId(),
            senderUuid,
            body,
            clientOpId
    );
    
    // 6. 使用点对点消息推送
    messagingTemplate.convertAndSendToUser(
            targetUserId,
            "/queue/chat.private",
            payload
    );
    
    // 7. 存储到 Redis（缓存层）
    chatHistoryService.appendPrivateMessage(payload, 100);
    
    return true;
}
```

**关键点**：

1. **幂等性**：使用 `clientOpId` 防止重复发送（数据库唯一约束）
2. **好友验证**：只有好友之间才能发送私聊消息
3. **点对点推送**：使用 `/user/{userId}/queue/chat.private`，确保只有目标用户能收到
4. **双重存储**：数据库（主存储）+ Redis（缓存层）

### 8.4 私聊历史消息

**查询流程**：`ChatHistoryServiceImpl.listPrivateMessages()`

```java
public List<ChatMessagePayload> listPrivateMessages(String userId1, String userId2, int limit) {
    // 1. 先尝试从 Redis 读取
    String sessionId = buildPrivateSessionId(userId1, userId2);
    String key = PRIVATE_KEY_PREFIX + sessionId;
    
    List<String> raw = stringRedisTemplate.opsForList().range(key, start, -1);
    if (raw != null && !raw.isEmpty()) {
        return deserializeMessages(raw);
    }
    
    // 2. Redis 为空，从数据库查询并回填 Redis
    UUID dbSessionId = chatSessionService.getPrivateSessionId(user1Uuid, user2Uuid);
    if (dbSessionId == null) {
        return Collections.emptyList();
    }
    
    List<ChatMessage> dbMessages = chatSessionService.listMessages(dbSessionId, fetch);
    
    // 3. 转换为 ChatMessagePayload 并写回 Redis
    List<ChatMessagePayload> result = convertToPayloads(dbMessages);
    writeBackToRedis(key, result);
    
    return result;
}
```

**关键点**：

1. **缓存优先**：优先从 Redis 读取，提高性能
2. **回填机制**：Redis 未命中时，从数据库查询并写回 Redis
3. **会话ID映射**：Redis 使用字符串格式的会话ID（`userId1:userId2`），数据库使用 UUID

### 8.5 未读消息计数

**实现**：`ChatSessionServiceImpl.countUnreadMessages()`

```java
public long countUnreadMessages(UUID sessionId, UUID userId) {
    // 1. 获取成员的最后已读时间
    ChatSessionMember member = memberRepository.findBySessionIdAndUserId(sessionId, userId)
            .orElse(null);
    
    if (member == null || member.getLastReadTime() == null) {
        // 从未打开过会话，返回所有未撤回的消息数（排除自己发的）
        return messageRepository.countAllUnreadMessages(sessionId, userId);
    }
    
    // 2. 计算未读消息数（基于时间戳比较）
    OffsetDateTime lastReadTime = member.getLastReadTime();
    return messageRepository.countUnreadMessagesAfter(sessionId, lastReadTime, userId);
}
```

**SQL 查询**：

```sql
SELECT COUNT(*) 
FROM chat_message 
WHERE session_id = ? 
  AND created_at > ? 
  AND sender_id != ? 
  AND is_recalled = false
```

### 8.6 标记已读

**实现**：`ChatSessionServiceImpl.markAsRead()`

```java
@Transactional
public void markAsRead(UUID sessionId, UUID userId, UUID messageId) {
    // 1. 获取成员记录
    ChatSessionMember member = memberRepository.findBySessionIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不是该会话的成员"));
    
    // 2. 确定已读消息ID和时间
    UUID readMessageId = messageId;
    OffsetDateTime readTime = null;
    
    if (readMessageId != null) {
        // 如果提供了 messageId，查询该消息的 created_at
        Optional<ChatMessage> readMessage = messageRepository.findById(readMessageId);
        if (readMessage.isPresent()) {
            readTime = readMessage.get().getCreatedAt();
        }
    } else {
        // 如果没有提供 messageId，查询最后一条消息
        Optional<ChatMessage> lastMessage = messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId);
        if (lastMessage.isPresent()) {
            readMessageId = lastMessage.get().getId();
            readTime = lastMessage.get().getCreatedAt();
        }
    }
    
    // 3. 更新已读状态
    member.setLastReadMessageId(readMessageId);
    member.setLastReadTime(readTime != null ? readTime : OffsetDateTime.now());
    memberRepository.save(member);
}
```

---

## 九、数据持久化

### 9.1 数据库表结构

#### 9.1.1 chat_session（会话表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| session_type | VARCHAR(20) | 会话类型（PRIVATE/ROOM/GROUP） |
| session_name | VARCHAR(100) | 会话名称（群聊时使用） |
| support_key | VARCHAR(120) | 私聊会话唯一键 |
| room_id | UUID | 房间ID（房间聊天时使用） |
| created_by | UUID | 创建人用户ID |
| last_message_id | UUID | 最后一条消息ID |
| last_message_time | TIMESTAMP | 最后消息时间 |
| member_count | INTEGER | 成员数量 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

**索引**：
- `idx_chat_session_type`：按会话类型查询
- `idx_chat_session_room`：按房间ID查询
- `idx_chat_session_last_message`：按最后消息时间排序
- `uk_chat_private_key`：私聊会话唯一约束
- `uk_chat_session_room_unique`：房间会话唯一约束

#### 9.1.2 chat_message（消息表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| session_id | UUID | 会话ID |
| sender_id | UUID | 发送者用户ID |
| client_op_id | VARCHAR(64) | 客户端操作ID（幂等键） |
| message_type | VARCHAR(20) | 消息类型（TEXT/IMAGE/FILE/SYSTEM） |
| content | TEXT | 消息内容 |
| extra_data | JSONB | 扩展数据（图片URL、文件信息等） |
| reply_to_message_id | UUID | 回复的消息ID |
| is_recalled | BOOLEAN | 是否已撤回 |
| recalled_at | TIMESTAMP | 撤回时间 |
| created_at | TIMESTAMP | 创建时间 |

**索引**：
- `idx_chat_message_session`：按会话ID查询
- `idx_chat_message_sender`：按发送者查询
- `idx_chat_message_session_time`：按会话和时间查询（复合索引）
- `idx_chat_message_reply`：按回复消息查询
- `uk_chat_message_client_op`：客户端操作ID唯一约束（幂等）

#### 9.1.3 chat_session_member（会话成员表）

| 字段 | 类型 | 说明 |
|-----|------|------|
| id | UUID | 主键 |
| session_id | UUID | 会话ID |
| user_id | UUID | 用户ID |
| member_role | VARCHAR(20) | 成员角色（MEMBER/ADMIN/OWNER） |
| last_read_message_id | UUID | 最后已读消息ID |
| last_read_time | TIMESTAMP | 最后已读时间 |
| left_at | TIMESTAMP | 离开时间（NULL表示仍在会话中） |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

**索引**：
- `idx_chat_session_member_session`：按会话ID查询
- `idx_chat_session_member_user`：按用户ID查询
- `uk_chat_session_member_unique`：会话和用户的唯一约束

### 9.2 持久化策略

#### 9.2.1 大厅消息

- **不持久化**：大厅消息是临时性的，不保存到数据库
- **Redis 缓存**：不缓存（实时广播即可）

#### 9.2.2 房间消息

- **Redis 缓存**：`chat:room:history:{roomId}` (List)，保留最近 50 条，TTL 24 小时
- **数据库持久化**：不持久化（房间是临时性的）

#### 9.2.3 私聊消息

- **Redis 缓存**：`chat:private:history:{sessionId}` (List)，保留最近 100 条，TTL 24 小时
- **数据库持久化**：全部持久化到 `chat_message` 表，支持历史查询

**持久化流程**：

```java
@Transactional
public ChatMessage savePrivateMessage(UUID sessionId, UUID senderId, String content, String clientOpId) {
    // 1. 幂等检查
    if (clientOpId != null) {
        messageRepository.findByClientOpId(clientOpId).ifPresent(existing -> {
            throw new IllegalStateException("消息已存在");
        });
    }
    
    // 2. 创建消息
    ChatMessage message = ChatMessage.builder()
            .sessionId(sessionId)
            .senderId(senderId)
            .clientOpId(clientOpId)
            .messageType(MessageType.TEXT)
            .content(content)
            .build();
    message = messageRepository.save(message);
    
    // 3. 更新会话的最后消息信息
    ChatSession session = sessionRepository.findById(sessionId).orElseThrow();
    session.setLastMessageId(message.getId());
    session.setLastMessageTime(message.getCreatedAt());
    sessionRepository.save(session);
    
    return message;
}
```

### 9.3 数据查询

#### 9.3.1 会话列表查询

**实现**：`ChatSessionServiceImpl.listUserSessions()`

```java
public List<SessionInfo> listUserSessions(UUID userId) {
    // 1. 通过成员表查询
    List<ChatSession> sessionsByMember = sessionRepository.findSessionsByUserId(userId);
    
    // 2. 通过消息表查询（补充成员表可能缺失的情况）
    List<ChatSession> sessionsByMessage = sessionRepository.findPrivateSessionsWithMessagesByUserId(userId, SessionType.PRIVATE);
    
    // 3. 合并去重
    Map<UUID, ChatSession> sessionMap = new HashMap<>();
    sessionsByMember.forEach(s -> sessionMap.put(s.getId(), s));
    sessionsByMessage.forEach(s -> sessionMap.putIfAbsent(s.getId(), s));
    
    // 4. 为每个会话查询未读数和最后消息
    return sessionMap.values().stream()
            .map(session -> {
                long unreadCount = countUnreadMessages(session.getId(), userId);
                ChatMessage lastMessage = messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(session.getId())
                        .orElse(null);
                return new SessionInfo(session, member, unreadCount, lastMessage, otherUserId);
            })
            .collect(Collectors.toList());
}
```

**SQL 查询**：

```sql
-- 通过成员表查询
SELECT s.* FROM chat_session s
INNER JOIN chat_session_member m ON s.id = m.session_id
WHERE m.user_id = ? AND m.left_at IS NULL

-- 通过消息表查询（私聊会话）
SELECT DISTINCT s.* FROM chat_session s
INNER JOIN chat_message m ON s.id = m.session_id
WHERE m.sender_id = ? AND s.session_type = 'PRIVATE'
```

#### 9.3.2 消息列表查询

**实现**：`ChatSessionServiceImpl.listMessages()`

```java
public List<ChatMessage> listMessages(UUID sessionId, int limit) {
    Pageable pageable = PageRequest.of(0, limit > 0 ? limit : 100);
    List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
    
    // 反转列表，按时间正序返回（最早的在前面）
    List<ChatMessage> result = new ArrayList<>(messages);
    result.sort(Comparator.comparing(ChatMessage::getCreatedAt));
    return result;
}
```

**SQL 查询**：

```sql
SELECT * FROM chat_message
WHERE session_id = ?
ORDER BY created_at DESC
LIMIT ?
```

---

## 十、Redis 缓存查询

### 10.1 缓存策略

#### 10.1.1 用户信息缓存

**Key**：`user:profile:{userId}`  
**类型**：String (JSON)  
**TTL**：2 小时  
**用途**：缓存用户昵称、头像等信息，减少 Feign 调用

**实现**：`UserProfileCacheService`

```java
public Optional<UserProfileView> get(String userId) {
    // 1. 从 Redis 读取
    String json = sessionRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
    if (json != null) {
        return Optional.of(objectMapper.readValue(json, UserProfileView.class));
    }
    
    // 2. 缓存未命中，调用 Feign（需要 Token）
    SystemUserClient.UserInfo userInfo = systemUserClient.getUserInfo(userId);
    if (userInfo != null) {
        UserProfileView view = new UserProfileView(...);
        // 3. 写入缓存
        put(view);
        return Optional.of(view);
    }
    
    return Optional.empty();
}
```

#### 10.1.2 房间消息缓存

**Key**：`chat:room:history:{roomId}`  
**类型**：List  
**TTL**：24 小时  
**最大长度**：50 条

**实现**：`ChatHistoryServiceImpl.appendRoomMessage()`

```java
public void appendRoomMessage(ChatMessagePayload payload, int maxSize) {
    String key = ROOM_KEY_PREFIX + payload.getRoomId();
    
    // 1. 序列化为 JSON
    String json = objectMapper.writeValueAsString(payload);
    
    // 2. 追加到 List 尾部
    stringRedisTemplate.opsForList().rightPush(key, json);
    
    // 3. 截断到指定大小（保留最近 N 条）
    int keep = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
    stringRedisTemplate.opsForList().trim(key, -keep, -1);
    
    // 4. 设置 TTL
    stringRedisTemplate.expire(key, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
}
```

#### 10.1.3 私聊消息缓存

**Key**：`chat:private:history:{sessionId}`  
**类型**：List  
**TTL**：24 小时  
**最大长度**：100 条

**实现**：`ChatHistoryServiceImpl.appendPrivateMessage()`

```java
public void appendPrivateMessage(ChatMessagePayload payload, int maxSize) {
    // 生成会话ID（按用户ID字典序排序）
    String sessionId = buildPrivateSessionId(payload.getSenderId(), payload.getTargetUserId());
    String key = PRIVATE_KEY_PREFIX + sessionId;
    
    // 追加到 List 并截断
    String json = objectMapper.writeValueAsString(payload);
    stringRedisTemplate.opsForList().rightPush(key, json);
    stringRedisTemplate.opsForList().trim(key, -maxSize, -1);
    stringRedisTemplate.expire(key, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
}
```

### 10.2 缓存查询流程

#### 10.2.1 房间历史消息

**流程**：`ChatHistoryServiceImpl.listRoomMessages()`

```java
public List<ChatMessagePayload> listRoomMessages(String roomId, int limit) {
    String key = ROOM_KEY_PREFIX + roomId;
    
    // 1. 获取 List 长度
    Long size = stringRedisTemplate.opsForList().size(key);
    if (size == null || size == 0) {
        return Collections.emptyList();
    }
    
    // 2. 从后往前读取（取最近 N 条）
    int fetch = limit > 0 ? limit : DEFAULT_MAX_SIZE;
    long start = Math.max(-fetch, -size);
    List<String> raw = stringRedisTemplate.opsForList().range(key, start, -1);
    
    // 3. 反序列化
    return raw.stream()
            .map(json -> objectMapper.readValue(json, ChatMessagePayload.class))
            .collect(Collectors.toList());
}
```

#### 10.2.2 私聊历史消息（带回填）

**流程**：`ChatHistoryServiceImpl.listPrivateMessages()`

```java
public List<ChatMessagePayload> listPrivateMessages(String userId1, String userId2, int limit) {
    String sessionId = buildPrivateSessionId(userId1, userId2);
    String key = PRIVATE_KEY_PREFIX + sessionId;
    
    // 1. 先尝试从 Redis 读取
    Long size = stringRedisTemplate.opsForList().size(key);
    if (size != null && size > 0) {
        List<String> raw = stringRedisTemplate.opsForList().range(key, start, -1);
        if (raw != null && !raw.isEmpty()) {
            return deserializeMessages(raw);
        }
    }
    
    // 2. Redis 为空，从数据库查询
    UUID dbSessionId = chatSessionService.getPrivateSessionId(user1Uuid, user2Uuid);
    if (dbSessionId == null) {
        return Collections.emptyList();
    }
    
    List<ChatMessage> dbMessages = chatSessionService.listMessages(dbSessionId, fetch);
    
    // 3. 转换为 ChatMessagePayload 并写回 Redis
    List<ChatMessagePayload> result = convertToPayloads(dbMessages);
    writeBackToRedis(key, result);
    
    return result;
}
```

**回填机制**：

```java
private void writeBackToRedis(String key, List<ChatMessagePayload> payloads) {
    // 1. 先清空（如果存在）
    stringRedisTemplate.delete(key);
    
    // 2. 批量写入（保持时间顺序）
    for (ChatMessagePayload payload : payloads) {
        String json = objectMapper.writeValueAsString(payload);
        stringRedisTemplate.opsForList().rightPush(key, json);
    }
    
    // 3. 截断到指定大小并设置 TTL
    int keep = fetch > 0 ? fetch : DEFAULT_PRIVATE_MAX_SIZE;
    stringRedisTemplate.opsForList().trim(key, -keep, -1);
    stringRedisTemplate.expire(key, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
}
```

### 10.3 缓存优化

#### 10.3.1 批量获取用户信息

**实现**：`UserProfileCacheService.batchGet()`

```java
public Map<String, UserProfileView> batchGet(
        List<String> userIds,
        Function<String, UserProfileView> remoteFetcher) {
    
    // 1. 去重
    List<String> distinctUserIds = userIds.stream().distinct().collect(Collectors.toList());
    
    // 2. 先批量从缓存获取
    Map<String, UserProfileView> result = new ConcurrentHashMap<>();
    List<String> cacheMissUserIds = new ArrayList<>();
    
    for (String userId : distinctUserIds) {
        Optional<UserProfileView> cached = get(userId);
        if (cached.isPresent()) {
            result.put(userId, cached.get());
        } else {
            cacheMissUserIds.add(userId);
        }
    }
    
    // 3. 缓存未命中的，并行从远程服务获取
    if (!cacheMissUserIds.isEmpty()) {
        cacheMissUserIds.parallelStream().forEach(userId -> {
            try {
                UserProfileView userInfo = remoteFetcher.apply(userId);
                if (userInfo != null) {
                    put(userInfo);  // 更新缓存
                    result.put(userId, userInfo);
                }
            } catch (Exception e) {
                log.warn("批量获取用户信息失败: userId={}", userId, e);
            }
        });
    }
    
    return result;
}
```

**使用场景**：查询会话列表时，批量获取对方用户信息

```java
// ChatSessionServiceImpl.listUserSessionsWithUserInfo()
List<String> otherUserIds = baseSessions.stream()
        .filter(info -> info.session().getSessionType() == SessionType.PRIVATE)
        .map(info -> info.otherUserId().toString())
        .distinct()
        .collect(Collectors.toList());

// 批量获取用户信息（自动处理缓存和并行获取）
Map<String, UserProfileView> userInfoMap = userProfileCacheService.batchGet(
        otherUserIds,
        userIdStr -> {
            SystemUserClient.UserInfo userInfo = systemUserClient.getUserInfo(userIdStr);
            return new UserProfileView(...);
        }
);
```

---

## 十一、前端交互

### 11.1 WebSocket 连接

**连接地址**（实际代码实现）：

```javascript
// 使用 SockJS 和 STOMP（实际代码：chatSocket.js）
import SockJS from 'sockjs-client'
import { Stomp } from '@stomp/stompjs'

const token = await ensureAuthenticated()
const wsUrl = `/chat-service/ws?access_token=${encodeURIComponent(token)}`
const socket = new SockJS(wsUrl)
const stomp = Stomp.over(socket)

// 配置心跳：客户端每 5 秒发送一次心跳，期望服务端每 5 秒发送一次心跳
stomp.heartbeat.outgoing = 5000
stomp.heartbeat.incoming = 5000

// 连接时传递 Token（通过 header）
const headers = { Authorization: 'Bearer ' + token }
stomp.connect(headers, 
    function(frame) {
        console.log('Connected: ' + frame);
        // 连接成功后，订阅消息
    },
    function(error) {
        console.error('Connection error: ' + error);
    }
);
```

**关键点**：

1. **连接地址**：使用相对路径 `/chat-service/ws`，通过 Gateway 路由（实际路径：`http://gateway:8080/chat-service/ws`）
2. **Token 传递**：通过 URL 参数 `access_token` 和 STOMP header `Authorization: Bearer {token}` 两种方式
3. **心跳配置**：客户端和服务端每 5 秒发送一次心跳，与服务端配置一致
4. **自动重连**：前端实现了自动重连机制（指数退避，最多 10 次）

### 11.2 消息订阅

**实际代码实现**（`chatSocket.js`）：

```javascript
// 订阅房间聊天
export function subscribeRoomChat(roomId, onEvent) {
    const client = getClient()  // 获取已连接的 STOMP 客户端
    const topic = `/topic/chat.room.${roomId}`
    
    const sub = client.subscribe(topic, (frame) => {
        try {
            const evt = JSON.parse(frame.body)
            onEvent(evt)
        } catch (err) {
            // 解析失败，静默处理
        }
    })
    
    subscriptions.set(topic, sub)
    return () => sub.unsubscribe()  // 返回取消订阅函数
}

// 订阅私聊消息（注意：使用 /user/queue/chat.private，Spring 会自动路由到对应用户）
export function subscribePrivateChat(onMessage) {
    const client = getClient()
    const topic = '/user/queue/chat.private'  // 不需要指定 userId，Spring 自动路由
    
    const sub = client.subscribe(topic, (frame) => {
        try {
            const payload = JSON.parse(frame.body)
            if (typeof onMessage === 'function') {
                onMessage(payload)
            }
        } catch (e) {
            // 解析失败，静默处理
        }
    })
    
    subscriptions.set(topic, sub)
    return () => sub.unsubscribe()  // 返回取消订阅函数
}

// 订阅系统通知
function subscribeUserNotify(onNotify) {
    const client = getClient()
    const topic = '/user/queue/notify'  // Spring 自动路由到对应用户
    
    const sub = client.subscribe(topic, (frame) => {
        try {
            const payload = JSON.parse(frame.body)
            onNotify?.(payload)
        } catch (err) {
            // 解析失败，静默处理
        }
    })
    
    subscriptions.set(topic, sub)
}

// 订阅系统踢线通知
function subscribeSystemKick(onKick) {
    const client = getClient()
    const topic = '/user/queue/system.kick'  // Spring 自动路由到对应用户
    
    const sub = client.subscribe(topic, (frame) => {
        try {
            const payload = JSON.parse(frame.body)
            onKick?.(payload)
        } catch {
            // 解析失败，静默处理
        }
    })
    
    subscriptions.set(topic, sub)
}
```

**订阅路径说明**：

| 订阅路径 | 说明 | 路由方式 |
|---------|------|---------|
| `/topic/chat.room.{roomId}` | 房间聊天（广播） | 所有订阅者都能收到 |
| `/user/queue/chat.private` | 私聊消息（点对点） | Spring 自动路由到对应用户（不需要在路径中指定 userId） |
| `/user/queue/notify` | 系统通知（点对点） | Spring 自动路由到对应用户 |
| `/user/queue/system.kick` | 踢线通知（点对点） | Spring 自动路由到对应用户 |

**注意**：点对点消息使用 `/user/queue/*` 格式，Spring WebSocket 会自动将 `/user/{userId}/queue/*` 路由到对应用户的 WebSocket 连接。前端只需要订阅 `/user/queue/*`，不需要在路径中指定 userId。

### 11.3 消息发送

**实际代码实现**（`chatSocket.js`）：

```javascript
// 发送房间消息
export function sendRoomChat(roomId, content, clientOpId) {
    const client = getClient()
    client.publish({
        destination: '/app/chat.room.send',
        body: JSON.stringify({
            roomId,
            content,
            clientOpId: clientOpId || crypto?.randomUUID?.() || String(Date.now()),
        }),
    })
}

// 发送私聊消息
export function sendPrivateChat(targetUserId, content, clientOpId) {
    const client = getClient()
    client.publish({
        destination: '/app/chat.private.send',
        body: JSON.stringify({
            targetUserId,
            content,
            clientOpId: clientOpId || crypto?.randomUUID?.() || String(Date.now()),
        }),
    })
}
```

**消息格式**：

**发送房间消息**：
```json
{
    "roomId": "room-uuid",
    "content": "消息内容",
    "clientOpId": "uuid-xxx"  // 可选，用于幂等
}
```

**发送私聊消息**：
```json
{
    "targetUserId": "user-uuid",
    "content": "消息内容",
    "clientOpId": "uuid-xxx"  // 可选，用于幂等
}
```

**接收消息格式**（`ChatMessagePayload`）：

```json
{
    "type": "PRIVATE",  // LOBBY | ROOM | PRIVATE
    "roomId": null,  // 房间ID（仅房间消息有值）
    "senderId": "user-uuid",
    "senderName": "用户昵称",
    "targetUserId": "user-uuid",  // 接收者ID（仅私聊消息有值）
    "content": "消息内容",
    "timestamp": 1234567890,  // 毫秒时间戳
    "clientOpId": "uuid-xxx"  // 客户端操作ID（用于去重）
}
```

### 11.4 REST API 调用

#### 11.4.1 查询会话列表

**实际代码实现**（`GlobalChat.jsx`）：

```javascript
// GET /chat-service/api/sessions
const sessionsResponse = await get('/chat-service/api/sessions')

// 响应格式
[
    {
        "sessionId": "uuid",
        "sessionType": "PRIVATE",
        "sessionName": null,
        "lastMessageTime": "2024-01-01T00:00:00Z",
        "unreadCount": 5,
        "lastMessage": "最后一条消息内容",
        "otherUserId": "user-uuid",  // 对方用户ID（仅私聊会话有值）
        "otherUserNickname": "用户昵称",  // 对方用户昵称（仅私聊会话有值）
        "otherUserAvatarUrl": "https://..."  // 对方用户头像（仅私聊会话有值）
    }
]
```

#### 11.4.2 查询私聊会话ID

**实际代码实现**（`GlobalChat.jsx`）：

```javascript
// GET /chat-service/api/sessions/private/{otherUserId}
// 用于获取私聊会话的 sessionId（仅查询，不创建）
const response = await get(`/chat-service/api/sessions/private/${friendId}`)

// 响应格式（如果会话存在）
{
    "sessionId": "uuid"
}

// 如果会话不存在（还没有发送过消息），返回 404
```

#### 11.4.3 查询历史消息

**房间历史消息**（`chatApi.js`）：

```javascript
// GET /chat-service/api/rooms/{roomId}/history?limit=50
export async function getRoomChatHistory(roomId, limit = 50) {
    const url = `/chat-service/api/rooms/${roomId}/history?limit=${limit}`
    const data = await authenticatedJsonFetch(url, { method: 'GET' })
    return Array.isArray(data) ? data : []
}

// 响应格式：ChatMessagePayload[]
[
    {
        "type": "ROOM",
        "roomId": "room-uuid",
        "senderId": "user-uuid",
        "senderName": "用户昵称",
        "content": "消息内容",
        "timestamp": 1234567890
    }
]
```

**私聊历史消息**（`GlobalChat.jsx`）：

```javascript
// GET /chat-service/api/private/{targetUserId}/history?limit=100
const response = await get(`/chat-service/api/private/${friendId}/history?limit=100`)

// 响应格式：ChatMessagePayload[]
[
    {
        "type": "PRIVATE",
        "roomId": null,
        "senderId": "user-uuid",
        "senderName": "用户昵称",
        "targetUserId": "user-uuid",
        "content": "消息内容",
        "timestamp": 1234567890,
        "clientOpId": "uuid-xxx"
    }
]
```

#### 11.4.4 标记已读

**实际代码实现**（`GlobalChat.jsx`）：

```javascript
// POST /chat-service/api/sessions/{sessionId}/read?messageId={messageId}
// messageId 可选，如果不提供则标记为最后一条消息
await post(`/chat-service/api/sessions/${sessionId}/read`)

// 或者指定消息ID
await post(`/chat-service/api/sessions/${sessionId}/read?messageId=${messageId}`)
```

#### 11.4.5 查询未读数

**实际代码实现**（`ChatSessionController.java`）：

```javascript
// GET /chat-service/api/sessions/{sessionId}/unread
const response = await fetch(`/chat-service/api/sessions/${sessionId}/unread`, {
    headers: {
        'Authorization': 'Bearer ' + token
    }
})

// 响应格式
{
    "sessionId": "uuid",
    "unreadCount": 5
}
```

### 11.5 前端使用示例

**完整的前端使用流程**（`GlobalChat.jsx`）：

```javascript
// 1. 连接 WebSocket（在 useGlobalChatWs 中）
useEffect(() => {
    if (!isAuthenticated) return
    
    const callbacks = {
        onConnect: () => {
            // 连接成功后，订阅私聊消息
            unsubscribePrivateChat = subscribePrivateChat((payload) => {
                // 收到私聊消息
                if (payload.type === 'PRIVATE' && payload.targetUserId === currentUserId) {
                    const threadId = `friend_${payload.senderId}`
                    addMessage({
                        text: payload.content,
                        type: 'other',
                        timestamp: new Date(payload.timestamp),
                        threadId,
                        messageId: payload.clientOpId || `${payload.timestamp}_${payload.senderId}`
                    })
                }
            })
        },
        onNotify: (notify) => {
            // 收到系统通知，广播给 Header 通知铃铛
            const event = new CustomEvent('gh-notify', { detail: notify })
            window.dispatchEvent(event)
        }
    }
    
    connectChatWebSocket(callbacks)
    
    return () => {
        unsubscribePrivateChat?.()
        removeChatWebSocketCallbacks(callbacks)
    }
}, [isAuthenticated, currentUserId])

// 2. 加载会话列表
useEffect(() => {
    if (!isAuthenticated) return
    
    const loadSessions = async () => {
        const sessionsResponse = await get('/chat-service/api/sessions')
        // 处理会话列表...
    }
    
    loadSessions()
}, [isAuthenticated])

// 3. 打开会话时，加载历史消息并标记已读
const handleOpenThread = async (threadId) => {
    if (threadId.startsWith('friend_')) {
        const friendId = threadId.replace('friend_', '')
        
        // 加载历史消息
        const response = await get(`/chat-service/api/private/${friendId}/history?limit=100`)
        response.forEach((msg) => {
            const isSelf = msg.senderId === currentUserId
            addMessage({
                text: msg.content,
                type: isSelf ? 'self' : 'other',
                timestamp: new Date(msg.timestamp),
                threadId,
                isHistory: true,
                messageId: msg.clientOpId || `${msg.timestamp}_${msg.senderId}`
            })
        })
        
        // 标记已读
        await markSessionAsRead(threadId, friendId)
    }
}

// 4. 发送私聊消息
const handleSend = () => {
    if (activeThreadId.startsWith('friend_')) {
        const friendId = activeThreadId.replace('friend_', '')
        const clientOpId = crypto.randomUUID()
        
        // 乐观更新（先本地显示）
        addMessage({
            text,
            type: 'self',
            threadId: activeThreadId,
            messageId: clientOpId
        })
        
        // 通过 WebSocket 发送
        sendPrivateChat(friendId, text, clientOpId)
    }
}
```

### 11.6 前端与后端交互总结

| 功能 | 交互方式 | 路径/方法 | 说明 |
|-----|---------|----------|------|
| WebSocket 连接 | WebSocket | `/chat-service/ws?access_token={token}` | 通过 Gateway 路由 |
| 订阅房间聊天 | WebSocket STOMP | `/topic/chat.room.{roomId}` | 广播消息 |
| 订阅私聊消息 | WebSocket STOMP | `/user/queue/chat.private` | 点对点消息（Spring 自动路由） |
| 订阅系统通知 | WebSocket STOMP | `/user/queue/notify` | 点对点消息（Spring 自动路由） |
| 发送房间消息 | WebSocket STOMP | `/app/chat.room.send` | 发送到房间 |
| 发送私聊消息 | WebSocket STOMP | `/app/chat.private.send` | 发送给指定用户 |
| 查询会话列表 | REST API | `GET /chat-service/api/sessions` | 获取所有会话（含未读数） |
| 查询私聊会话ID | REST API | `GET /chat-service/api/sessions/private/{otherUserId}` | 仅查询，不创建 |
| 查询房间历史 | REST API | `GET /chat-service/api/rooms/{roomId}/history?limit=50` | 默认 50 条 |
| 查询私聊历史 | REST API | `GET /chat-service/api/private/{targetUserId}/history?limit=100` | 默认 100 条 |
| 标记已读 | REST API | `POST /chat-service/api/sessions/{sessionId}/read?messageId={messageId}` | messageId 可选 |
| 查询未读数 | REST API | `GET /chat-service/api/sessions/{sessionId}/unread` | 返回未读消息数 |

---

## 总结

### 关键技术点

1. **WebSocket + STOMP**：使用 Spring WebSocket 和 STOMP 协议实现实时消息推送
2. **JWT 鉴权**：通过 `WebSocketAuthChannelInterceptor` 在 CONNECT 时验证 Token
3. **Token 传递**：
   - 使用 `WebSocketTokenStore`（Redis + 内存降级）存储 Token，供 Feign 调用使用
   - **关键优化**：使用 `sid`（loginSessionId）作为主要 key，而不是 `wsSessionId`
   - **原因**：`sid` 在整个登录生命周期内不变，即使 token 刷新也不会变，确保 token 可获取
   - **双重 key 策略**：同时保存 `ws:token:{loginSessionId}` 和 `ws:token:{wsSessionId}`，确保向后兼容
4. **Feign 调用**：
   - 通过 `JwtTokenHolder`（ThreadLocal）和 Feign 拦截器自动传递 Token
   - **WebSocket 场景**：在 CONNECT 时保存 Token 到 Redis，在 SEND 消息时恢复 Token 到 ThreadLocal
   - **自动恢复机制**：优先使用 `loginSessionId` 获取 token，降级使用 `wsSessionId`，并自动修复存储
5. **数据持久化**：私聊消息持久化到 PostgreSQL，房间消息仅缓存到 Redis
6. **缓存策略**：用户信息缓存（2小时），消息历史缓存（24小时），支持回填机制
7. **熔断降级**：使用 Resilience4j 实现 Feign 调用的熔断和降级

### 架构优势

1. **低耦合**：通过 Feign Client 和 Kafka 事件实现服务间解耦
2. **高可用**：熔断降级、缓存策略、Token 存储降级
3. **可扩展**：支持多实例部署（Redis 共享 Token 和缓存）
4. **性能优化**：批量获取用户信息、并行处理、缓存优先

### 注意事项

1. **Token 过期**：Token TTL 为 1 小时，过期后需要前端重新连接
2. **多实例部署**：必须使用 Redis 存储 Token（不能使用内存存储）
3. **ThreadLocal 清理**：不要在拦截器中清理 ThreadLocal，让 Spring 自动管理
4. **好友验证降级**：生产环境建议将降级策略改为拒绝发送（确保安全性）
5. **Token 刷新**：
   - 当 access_token 刷新时，`sid`（loginSessionId）保持不变
   - 使用 `sid` 作为主要 key，确保 token 刷新后仍能正确获取
   - 建议：前端在 token 刷新后重新建立 WebSocket 连接，确保使用最新的 token
6. **Redis Key 格式**：
   - 主要 key：`ws:token:{loginSessionId}` - 使用 JWT 的 `sid` claim
   - 备用 key：`ws:token:{wsSessionId}` - 使用 WebSocket sessionId
   - 两个 key 同时保存，确保向后兼容和可靠性

---

**文档版本**：v1.0  
**最后更新**：2024年
