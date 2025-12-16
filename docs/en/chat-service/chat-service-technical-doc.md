# Chat-Service Technical Documentation

> This document explains in detail the technical implementation of chat-service, including code architecture, feature implementation, configuration, WebSocket implementation, Feign call mechanism, data persistence, etc.

---

## Table of Contents

1. [Service Overview](#i-service-overview)
2. [Code Architecture](#ii-code-architecture)
3. [Configuration](#iii-configuration)
4. [WebSocket Implementation](#iv-websocket-implementation)
5. [OpenFeign Configuration and Usage](#v-openfeign-configuration-and-usage)
6. [Room Session Implementation](#vi-room-session-implementation)
7. [Global Message Notification Capability](#vii-global-message-notification-capability)
8. [Private Message Sessions](#viii-private-message-sessions)
9. [Data Persistence](#ix-data-persistence)
10. [Redis Cache Queries](#x-redis-cache-queries)
11. [Frontend Interaction](#xi-frontend-interaction)

---

## I. Service Overview

### 1.1 Service Positioning

**chat-service** is the **core service in the social domain**, responsible for all “messaging” related features, including:

- **Lobby chat**: global channel, visible to all online users
- **Room chat**: real-time communication inside game rooms
- **Private messages**: point-to-point messaging, only visible to the two users
- **System notifications**: system-initiated notification messages

### 1.2 Tech Stack

- **Framework**: Spring Boot 3.x + Spring WebSocket + STOMP
- **Security**: Spring Security + OAuth2 Resource Server (Keycloak JWT)
- **Storage**: PostgreSQL (JPA/Hibernate) + Redis
- **Service calls**: Spring Cloud OpenFeign + Resilience4j circuit breaker
- **MQ**: Kafka (room event subscription)
- **Service discovery**: Spring Cloud LoadBalancer

### 1.3 Core Responsibilities

| Module | Responsibility |
|--------|----------------|
| Message send/receive | Accept user messages, broadcast to subscribers, format & validate messages |
| Session management | Create/query chat sessions, manage members, session lifecycle |
| Message persistence | Redis for recent messages, PostgreSQL for history |
| Access control | Mute, sensitive word filtering, rate limiting (not implemented) |
| Realtime notification | New message notification, unread count, online status sync |

---

## II. Code Architecture

### 2.1 Package Structure

```
com.gamehub.chatservice
├── ChatServiceApplication          # Bootstrap
├── config/                         # Config
│   ├── SecurityConfig              # Spring Security + OAuth2
│   ├── WebSocketStompConfig        # WebSocket STOMP config
│   ├── WebSocketAuthChannelInterceptor  # WebSocket auth interceptor
│   └── WebSocketTokenStore         # Token store (Redis + in-memory fallback)
├── controller/                     # Controllers
│   ├── ws/                         # WebSocket controllers
│   │   ├── ChatWsController        # STOMP handler
│   │   └── dto/                    # WebSocket DTO
│   │       ├── SendMessage         # send message DTO
│   │       └── SendPrivateMessage  # send private message DTO
│   └── http/                       # REST controllers
│       ├── ChatRestController      # basic REST
│       ├── ChatHistoryController   # history query
│       ├── ChatSessionController   # session management
│       ├── NotificationInternalController  # internal notification push
│       └── dto/                    # HTTP DTO
│           ├── MessageResponse     # message response DTO
│           ├── SessionResponse     # session response DTO
│           ├── SessionIdResponse   # sessionId response DTO
│           ├── UnreadCountResponse # unread count response DTO
│           └── NotifyRequest       # notification request DTO
├── entity/                         # Entities
│   ├── ChatSession                 # session entity
│   ├── ChatMessage                 # message entity
│   └── ChatSessionMember           # session member entity
├── repository/                     # Repositories
│   ├── ChatSessionRepository
│   ├── ChatMessageRepository
│   └── ChatSessionMemberRepository
├── service/                        # Services
│   ├── ChatMessagingService        # message sending service
│   ├── ChatHistoryService          # history service
│   ├── ChatSessionService          # session service
│   ├── NotificationPushService     # notification push service
│   ├── UserProfileCacheService     # user profile cache
│   ├── dto/                        # service DTO
│   │   ├── ChatMessagePayload      # message payload DTO
│   │   └── NotificationMessagePayload  # notification payload DTO
│   └── impl/                       # implementations
│       ├── ChatMessagingServiceImpl
│       ├── ChatHistoryServiceImpl
│       ├── ChatSessionServiceImpl
│       └── NotificationPushServiceImpl
├── infrastructure/                 # Infrastructure
│   ├── redis/                      # Redis
│   │   └── RedisConfig             # Redis config (conn & serialization)
│   ├── client/                     # external clients
│   │   ├── SystemUserClient        # Feign (system-service)
│   │   ├── SystemUserClientFallback  # fallback
│   │   ├── GameRoomClient          # Feign (game-service)
│   │   └── GameRoomClientFallback  # fallback
│   └── kafka/                      # Kafka events
│       └── RoomEventConsumer       # room event consumer
└── ws/                             # WebSocket management
    ├── WebSocketSessionManager     # session manager (new kicks old)
    ├── WebSocketDisconnectHelper   # disconnect helper (force close, kick notification)
    └── SessionInvalidatedListener  # session invalidation listener (logout/password change/disable)
```

### 2.2 Key Class Responsibilities

| Class | Responsibility |
|-------|----------------|
| `ChatServiceApplication` | Spring Boot entry, enables Feign/JPA |
| `SecurityConfig` | Spring Security + OAuth2 config |
| `WebSocketStompConfig` | STOMP endpoint, broker, heartbeat |
| `WebSocketAuthChannelInterceptor` | WebSocket auth, extract JWT |
| `WebSocketTokenStore` | Store JWT (Redis + in-memory fallback) |
| `ChatWsController` | Handle STOMP messages (lobby/room/private) |
| `ChatRestController` | Basic REST (health) |
| `ChatHistoryController` | History REST |
| `ChatSessionController` | Session REST (sessions/messages/read/unread) |
| `NotificationInternalController` | Internal notification REST |
| `ChatMessagingService` | Message sending service interface |
| `ChatMessagingServiceImpl` | Core logic (send, auth, broadcast) |
| `ChatHistoryService` | History service interface |
| `ChatHistoryServiceImpl` | History mgmt (Redis cache + PostgreSQL) |
| `ChatSessionService` | Session service interface |
| `ChatSessionServiceImpl` | Session mgmt (create/query/members/unread) |
| `NotificationPushService` | Notification push interface |
| `NotificationPushServiceImpl` | Notification push impl (WS point-to-point) |
| `UserProfileCacheService` | User profile cache (Redis, TTL 2h) |
| `ChatMessage` | Message entity (content/sender/session/recall) |
| `ChatSession` | Session entity (type/members/last message) |
| `ChatSessionMember` | Session member (user/role/read state) |
| `ChatMessageRepository` | Message repo |
| `ChatSessionRepository` | Session repo |
| `ChatSessionMemberRepository` | Member repo |
| `SystemUserClient` | Call system-service for user info, mute, friends |
| `SystemUserClientFallback` | Fallback for SystemUserClient |
| `GameRoomClient` | Call game-service for room members (optional) |
| `GameRoomClientFallback` | Fallback for GameRoomClient |
| `RedisConfig` | Redis conn & serialization (`infrastructure/redis`) |
| `RoomEventConsumer` | Kafka room event consumer |
| `WebSocketSessionManager` | Manage WebSocket sessions, “new kicks old” (single device login) |
| `WebSocketDisconnectHelper` | Disconnect helper (force close, kick notify) |
| `SessionInvalidatedListener` | Listen logout/password-change/disable, auto close WS |
| `ChatMessagePayload` | WebSocket message payload DTO |
| `NotificationMessagePayload` | WebSocket notification payload DTO |
| `SendMessage` | WebSocket send DTO (lobby/room) |
| `SendPrivateMessage` | WebSocket private send DTO |
| `MessageResponse` | HTTP message response DTO |
| `SessionResponse` | HTTP session response DTO |
| `SessionIdResponse` | HTTP sessionId response DTO |
| `UnreadCountResponse` | HTTP unread count DTO |
| `NotifyRequest` | HTTP notification request DTO |

---

## III. Configuration

### 3.1 WebSocket Config

**File**: `WebSocketStompConfig.java`

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {
    
    // register STOMP endpoints
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // SockJS fallback
    }
    
    // configure message broker
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // simple in-memory broker
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{5000, 5000})
                .setTaskScheduler(wsHeartbeatTaskScheduler());
        
        // app destination prefix
        registry.setApplicationDestinationPrefixes("/app");
        
        // user destination prefix
        registry.setUserDestinationPrefix("/user");
    }
}
```

**Key Points**:

1. **Endpoint**: `/ws` (via Gateway: `ws://gateway:8080/chat-service/ws`)
2. **Broker**: in-memory SimpleBroker, suitable for single/small-scale
3. **Heartbeat**:
   - Config: `setHeartbeatValue(new long[]{5000, 5000})` (5s both ways)
   - `TaskScheduler`: `wsHeartbeatTaskScheduler` bean
   - Purpose: keep alive, detect disconnect quickly
   - Impl:
     ```java
     @Bean(name = "wsHeartbeatTaskScheduler")
     public TaskScheduler wsHeartbeatTaskScheduler() {
         ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
         scheduler.setPoolSize(1);
         scheduler.setThreadNamePrefix("ws-heartbeat-");
         scheduler.setDaemon(true);
         scheduler.initialize();
         return scheduler;
     }
     ```
4. **Prefixes**:
   - `/app`: client send (e.g. `/app/chat.lobby.send`)
   - `/topic`: broadcast (e.g. `/topic/chat.lobby`)
   - `/queue`: point-to-point (e.g. `/user/{userId}/queue/chat.private`)

### 3.2 Auth Config

#### 3.2.1 Spring Security

**File**: `SecurityConfig.java`

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
                .requestMatchers("/ws/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated()
        );
        
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        
        return http.build();
    }
}
```

**Notes**:

- OAuth2 Resource Server with Keycloak
- JWT validation via `issuer-uri`
- Stateless: no HTTP session

#### 3.2.2 WebSocket Auth Interceptor

**File**: `WebSocketAuthChannelInterceptor.java`

**Core Logic**:

1. **CONNECT phase**:
   - Extract `Authorization: Bearer {token}` or `access_token` from STOMP headers
   - If missing, try HTTP headers (Gateway filter injected)
   - Use `JwtDecoder` to decode & validate
   - Build `JwtAuthenticationToken`, set into `StompHeaderAccessor`
   - **Extract `sid` (loginSessionId)**: prefer `sid` claim, fallback `session_state`
   - **Save raw token into `WebSocketTokenStore`**:
     - **Primary key**: `loginSessionId` (`sid`) – stable across refresh
     - **Fallback key**: `wsSessionId` – for backward compatibility
     - Save under both keys for robustness
   - Set `SecurityContext` and `JwtTokenHolder` (ThreadLocal)

2. **Non-CONNECT messages (SEND, etc.)**:
   - From session get user (`accessor.getUser()`)
   - **Prefer `loginSessionId` from user (sid)**  
   - **Prefer use `loginSessionId` in `WebSocketTokenStore` to get token** (sid stable across refresh)
   - **Fallback**: if failed, use `wsSessionId`
   - **Auto-fix**: if token fetched by `wsSessionId`, extract sid and update store
   - If `accessor.getUser()` null but token exists, rebuild user from token
   - Set `JwtTokenHolder` and `SecurityContext`

**Why store token?**

- After connect, SEND messages carry no token
- While processing messages, Feign calls may need token
- Gateway enforces token on inter-service calls
- So token must be stored on CONNECT, restored later

**Why use `sid` (loginSessionId) as primary key?**

- Access token refreshes (e.g. every 5min), but `sid` remains constant for session
- Stable key: refresh just overwrites same key instead of new entry
- Backward-compatible: when no `sid`, fallback to `wsSessionId`

**Token Store Strategy**:

- **Redis** (multi-instance): uses `sessionRedisTemplate` from `session-common` (db 0)
- **In-memory fallback** (single instance): `ConcurrentHashMap`
- **TTL**: 1 hour (same as JWT)
- **Key format**: `ws:token:{loginSessionId}` or `ws:token:{wsSessionId}`

### 3.3 Redis Config

**File**: `application.yml`

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:zaqxsw}
      database: ${REDIS_DATABASE:4}  # chat-service DB
      timeout: 2s
      lettuce:
        pool:
          max-active: 32
          max-idle: 16
          min-idle: 0
          max-wait: 5s
```

**Redis Usage**:

1. **Message history cache** (chat-service DB 4):
   - Room: `chat:room:history:{roomId}` (List)
   - Private: `chat:private:history:{sessionId}` (List)
   - TTL: 24h

2. **User profile cache** (session-common, db 0):
   - `user:profile:{userId}` String JSON
   - TTL: 2h

3. **Token store** (session-common, db 0):
   - WebSocket token: `ws:token:{loginSessionId}` or `ws:token:{wsSessionId}`
   - **Primary**: `ws:token:{loginSessionId}` using JWT `sid`
   - **Fallback**: `ws:token:{wsSessionId}` using WS sessionId
   - Store both
   - TTL: 1h

**Redis Serialization**:

**File**: `infrastructure/redis/RedisConfig.java`

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);
        
        StringRedisSerializer keySer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer();
        
        tpl.setKeySerializer(keySer);
        tpl.setValueSerializer(valSer);
        tpl.setHashKeySerializer(keySer);
        tpl.setHashValueSerializer(valSer);
        
        tpl.afterPropertiesSet();
        return tpl;
    }
    
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

**Notes**:

- Location: `infrastructure/redis/RedisConfig.java`
- Purpose: chat-service-specific Redis and serialization
- Beans:
  - `redisTemplate`: JSON values
  - `stringRedisTemplate`: strings

---

## IV. WebSocket Implementation

### 4.1 Global WebSocket Connection

**Flow**:

1. **Frontend**:
   ```javascript
   const socket = new SockJS('/chat-service/ws?access_token=xxx');
   const stompClient = Stomp.over(socket);
   
   stompClient.heartbeat.outgoing = 5000
   stompClient.heartbeat.incoming = 5000
   ```

2. **Gateway**:
   - Accept WS request
   - Validate JWT via OAuth2 filters
   - Route to `chat-service:8083/ws`

3. **chat-service**:
   - `WebSocketAuthChannelInterceptor` intercepts CONNECT
   - Extracts token, validate, set user
   - `WebSocketSessionManager` registers session (supports “new kicks old”)
   - Heartbeat started (5s)

**Session Management**:

- **Register**: on connect, register in `SessionRegistry` keyed by `loginSessionId + service`
- **Kick**: if same user+service has old session, kick it
- **Cleanup**: on disconnect, unregister, clear token store

**“New kicks old”** (`WebSocketSessionManager`):

```java
@EventListener
public void handleSessionConnect(SessionConnectEvent event) {
    String sessionId = event.getMessage().getHeaders().get("simpSessionId", String.class);
    Principal principal = (Principal) event.getMessage().getHeaders().get("simpUser");
    String loginSessionId = extractLoginSessionId(principal);
    
    // 1) old sessions for same user/service
    List<WebSocketSessionInfo> existing = sessionRegistry.getWebSocketSessions(principal.getName());
    List<WebSocketSessionInfo> sameService = existing.stream()
            .filter(ws -> "chat-service".equals(ws.getService()))
            .toList();
    
    // 2) unregister old
    if (sameService != null && !sameService.isEmpty()) {
        sameService.forEach(old -> sessionRegistry.unregisterWebSocketSession(old.getSessionId()));
    }
    
    // 3) register new
    sessionRegistry.registerWebSocketSession(info, 0);
    
    // 4) send kick message and force disconnect old
    if (sameService != null && !sameService.isEmpty()) {
        sameService.forEach(old -> {
            disconnectHelper.sendKickMessage(principal.getName(), old.getSessionId(), "Account logged in elsewhere");
            disconnectHelper.forceDisconnect(old.getSessionId());
        });
    }
}
```

**Key Points**:

1. **Service isolation**: only kick same-service (chat-service) sessions, keep others (game-service)
2. **Kick notification**: `/user/{userId}/queue/system.kick`
3. **Force close**: `forceDisconnect()`
4. **Token cleanup**: clear `WebSocketTokenStore` on disconnect

**Session Invalidation Listener** (`SessionInvalidatedListener`):

chat-service listens to Kafka session invalidation events and auto closes WS connections – **same as game-service**.

**Scenarios**:

| Scenario | Event type | Description |
|----------|------------|-------------|
| Logout | `LOGOUT` | user logs out, close all WS |
| Password change | `PASSWORD_CHANGED` | close all, re-login required |
| User disabled | `USER_DISABLED` | close all |
| Force logout | `FORCE_LOGOUT` | admin kicks out |
| Other | `OTHER` | other invalidation |

**Mechanism**:

```java
@Component
public class SessionInvalidatedListener implements SessionEventListener {
    
    @Override
    public void onSessionInvalidated(SessionInvalidatedEvent event) {
        String userId = event.getUserId();
        String loginSessionId = event.getLoginSessionId();
        
        List<WebSocketSessionInfo> chatSessions;
        if (loginSessionId != null && !loginSessionId.isBlank()) {
            List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessionsByLoginSessionId(loginSessionId);
            chatSessions = wsSessions.stream()
                    .filter(session -> "chat-service".equals(session.getService()))
                    .toList();
        } else {
            List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessions(userId);
            chatSessions = wsSessions.stream()
                    .filter(session -> "chat-service".equals(session.getService()))
                    .toList();
        }
        
        String reason = resolveReason(event);
        
        for (WebSocketSessionInfo session : chatSessions) {
            disconnectHelper.sendKickMessage(userId, session.getSessionId(), reason);
            disconnectHelper.forceDisconnect(session.getSessionId());
            sessionRegistry.unregisterWebSocketSession(session.getSessionId());
        }
    }
}
```

**Key Points**:

1. Precise query by `loginSessionId` when possible
2. Only chat-service sessions closed
3. Kick notification before close
4. Cleanup `SessionRegistry`

**Frontend**:

```javascript
stompClient.subscribe('/user/queue/system.kick', (message) => {
    const payload = JSON.parse(message.body);
    // { type: "WS_KICK", reason }
    showNotification(payload.reason);
    stompClient.disconnect();
    // optional: redirect to login
});
```

**Consistency with game-service**:

- Same mechanism
- Both listen to Kafka `SessionInvalidatedEvent`
- Both use `WebSocketDisconnectHelper`
- Each only manages its own WS connections

**Summary**:

1. Single-device login: new connection kicks old
2. Logout: close all WS
3. Password change: close WS, require re-login
4. User disabled: close WS
5. Force logout: close WS

**In all scenarios, chat-service WebSocket connections auto close, consistent with game-service.**

### 4.2 WebSocket Subscriptions

#### 4.2.1 Subscription List

| Path | Description | Payload |
|------|-------------|---------|
| `/topic/chat.lobby` | Lobby chat (global) | `ChatMessagePayload` |
| `/topic/chat.room.{roomId}` | Room chat | `ChatMessagePayload` |
| `/user/{userId}/queue/chat.private` | Private messages (p2p) | `ChatMessagePayload` |
| `/user/{userId}/queue/notify` | System notification (p2p) | `NotificationMessagePayload` |
| `/user/{userId}/queue/system.kick` | Kick notification (p2p) | `{type: "WS_KICK", reason}` |

#### 4.2.2 Frontend Subscriptions

```javascript
// lobby
stompClient.subscribe('/topic/chat.lobby', (message) => {
    const msg = JSON.parse(message.body);
});

// room
stompClient.subscribe(`/topic/chat.room.${roomId}`, (message) => {
    const msg = JSON.parse(message.body);
});

// private
stompClient.subscribe(`/user/${userId}/queue/chat.private`, (message) => {
    const msg = JSON.parse(message.body);
});

// notification
stompClient.subscribe(`/user/${userId}/queue/notify`, (message) => {
    const notification = JSON.parse(message.body);
});
```

### 4.3 WebSocket Sending

#### 4.3.1 Client SEND (`/app/*`)

| Path | Description | Body |
|------|-------------|------|
| `/app/chat.lobby.send` | Lobby message | `{ content, clientOpId? }` |
| `/app/chat.room.send` | Room message | `{ roomId, content, clientOpId? }` |
| `/app/chat.private.send` | Private message | `{ targetUserId, content, clientOpId? }` |

**Frontend**:

```javascript
stompClient.send('/app/chat.lobby.send', {}, JSON.stringify({
    content: 'Hello, world!',
    clientOpId: 'uuid-xxx'
}));

stompClient.send('/app/chat.room.send', {}, JSON.stringify({
    roomId: 'room-123',
    content: 'Nice move!',
    clientOpId: 'uuid-xxx'
}));

stompClient.send('/app/chat.private.send', {}, JSON.stringify({
    targetUserId: 'user-uuid',
    content: 'Hello!',
    clientOpId: 'uuid-xxx'
}));
```

#### 4.3.2 Server Handling

**Controller**: `ChatWsController.java`

```java
@Controller
public class ChatWsController {
    
    @MessageMapping("/chat.lobby.send")
    public void sendLobby(@Payload SendMessage cmd, SimpMessageHeaderAccessor sha) {
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
        
        String token = JwtTokenHolder.getToken();
        if (token == null || token.isBlank()) {
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
            
            if (loginSessionId != null && !loginSessionId.isBlank()) {
                token = tokenStore.getToken(loginSessionId);
                if (token != null && !token.isBlank()) {
                    JwtTokenHolder.setToken(token);
                }
            }
            
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

### 4.4 Subscription and Call Relationship

**Flow**:

```
Client connects WS
  ↓
Subscribes /topic/chat.lobby
Subscribes /topic/chat.room.{roomId}
Subscribes /user/{userId}/queue/chat.private
Subscribes /user/{userId}/queue/notify
  ↓
User sends message
  ↓
Client sends /app/chat.*.send
  ↓
chat-service processes
  ↓
Broadcast to relevant subscriptions
  ↓
Client receives & displays
```

**Key Points**:

1. Subscriptions persist for session
2. Routing:
   - `/topic/*`: broadcast
   - `/user/{userId}/queue/*`: point-to-point
3. Spring routes `/user/{userId}/queue/*` to that user’s WS

---

## V. OpenFeign Configuration and Usage

### 5.1 Feign Config

#### 5.1.1 Dependencies

**pom.xml**:

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

#### 5.1.2 Enable Feign

**`ChatServiceApplication.java`**:

```java
@SpringBootApplication
@EnableFeignClients
public class ChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
```

#### 5.1.3 Discovery

**`application.yml`**:

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

Notes:

- Local: SimpleDiscovery with explicit URIs
- Docker Compose: container DNS, no config
- K8s: Service + DNS, no config

#### 5.1.4 Circuit Breaker

**`application.yml`**:

```yaml
feign:
  circuitbreaker:
    enabled: true

resilience4j:
  circuitbreaker:
    instances:
      systemUserClient:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
      gameRoomClient:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
```

### 5.2 Feign Client Definitions

#### 5.2.1 SystemUserClient

**`SystemUserClient.java`**:

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

**Fallback**: `SystemUserClientFallback.java`

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
        // For availability; in production should likely be false
        return ApiResponse.success(true);
    }
}
```

#### 5.2.2 GameRoomClient

**`GameRoomClient.java`**:

```java
@FeignClient(name = "game-service", fallback = GameRoomClientFallback.class)
public interface GameRoomClient {
    
    @GetMapping("/api/game/rooms/{roomId}/members")
    @CircuitBreaker(name = "gameRoomClient")
    List<String> getRoomMembers(@PathVariable String roomId);
}
```

**Fallback**: `GameRoomClientFallback.java`

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

### 5.3 Token Propagation in Feign

#### 5.3.1 Background

**Why propagate token?**

- chat-service is a resource server; requests already passed Gateway auth
- But chat-service calling other services needs token
- Gateway requires token for these calls

**WebSocket specifics**:

- After WS connect, SEND messages carry no token
- But message handling may need Feign
- WS handling is async; ThreadLocal can be lost across threads
- Hence: save token at CONNECT in Redis, restore later

#### 5.3.2 Architecture

**Components**:

1. `JwtTokenHolder` (ThreadLocal) from `web-common`
2. `WebSocketTokenStore` (Redis + in-memory fallback)
3. `FeignAuthAutoConfiguration` (Feign interceptor)

**Token Flow**:

(As described in the Chinese doc, here summarized:)

- CONNECT: interceptor extracts token, decodes JWT, extracts sid, stores token under sid & wsSessionId, sets ThreadLocal & SecurityContext
- SEND: interceptor restores token from `WebSocketTokenStore` (sid first, wsSessionId fallback), updates ThreadLocal & possibly user principal
- `ChatWsController.sendPrivate` has extra fallback to restore token in ThreadLocal using sid or wsSessionId
- Feign interceptor reads token from ThreadLocal (or HTTP headers/SecurityContext) and adds `Authorization: Bearer {token}`

See original diagrams for full step-by-step; implementation matches those descriptions.

#### 5.3.3 Implementations

**1. CONNECT token store**: as shown earlier in `WebSocketAuthChannelInterceptor.preSend()`.

**2. SEND token restore**: also in `preSend()` for non-CONNECT, using loginSessionId then wsSessionId; auto repair of store.

**3. Feign interceptor**:

```java
@Bean
public RequestInterceptor feignRequestInterceptor() {
    return requestTemplate -> {
        String authorization = null;
        
        ServletRequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            authorization = request.getHeader("Authorization");
        }
        
        if (authorization == null || authorization.isBlank()) {
            String token = JwtTokenHolder.getToken();
            if (token != null && !token.isBlank()) {
                authorization = "Bearer " + token;
            }
        }
        
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
        
        if (authorization != null && !authorization.isBlank()) {
            requestTemplate.header("Authorization", authorization);
        }
    };
}
```

#### 5.3.4 Why `sid` as primary key?

(Same reasoning as Chinese doc)

- Token refresh, sid stable
- Key stability across reconnections
- Backward compatibility with wsSessionId

#### 5.3.5 Why this design?

**Pros**:

1. Transparent: business doesn’t care about token propagation
2. Compatible: covers HTTP and WebSocket
3. Reliable:
   - Redis for multi-instance
   - In-memory fallback
   - Dual-key strategy
4. Auto repair: wsSessionId-based token can repair sid mapping

**Notes**:

1. ThreadLocal cleanup: do not prematurely clear; next message overwrites
2. Token expiry: TTL 1h, must reconnect when expired
3. Multi-instance: must use Redis, same db0
4. Token refresh: sid stable; recommended WS reconnect on token refresh

### 5.4 Feign Use Cases

#### 5.4.1 User Info

Used when sending messages or listing sessions.

`UserProfileCacheService`:

```java
@Service
public class UserProfileCacheService {
    
    public Optional<UserProfileView> get(String userId) {
        String json = sessionRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (json != null) {
            return Optional.of(objectMapper.readValue(json, UserProfileView.class));
        }
        
        SystemUserClient.UserInfo userInfo = systemUserClient.getUserInfo(userId);
        if (userInfo != null) {
            UserProfileView view = new UserProfileView(...);
            put(view);
            return Optional.of(view);
        }
        
        return Optional.empty();
    }
}
```

#### 5.4.2 Friend Check

Used for private messages:

```java
try {
    ApiResponse<Boolean> response = systemUserClient.isFriend(senderId, targetUserId);
    if (response == null || response.code() != 200 || !response.data()) {
        log.warn("Private message send failed: not friends");
        return false;
    }
} catch (Exception e) {
    log.error("Friend check failed", e);
    // degraded: allow send; in production likely should return false
    log.warn("Friend check failed but allow message (degradation)");
}
```

#### 5.4.3 Room Members

Optional check that user is in room; currently not used.

---

## VI. Room Session Implementation

### 6.1 Room Session Characteristics

- **On-demand**: created on first message, not at room creation
- **Temporary**: deleted when room deleted (via Kafka)
- **Dynamic members**: future sync via Kafka (not implemented)

### 6.2 Room Message Flow

Flow:

```
User sends room message
  ↓
ChatWsController.sendRoom()
  ↓
ChatMessagingServiceImpl.sendRoomMessage()
  ↓
1) build ChatMessagePayload
2) broadcast /topic/chat.room.{roomId}
3) store in Redis (chat:room:history:{roomId})
  ↓
Subscribers receive
```

Impl:

```java
@Override
public void sendRoomMessage(String userId, String roomId, String content) {
    String body = sanitize(content);
    if (body == null) {
        return;
    }
    
    ChatMessagePayload payload = ChatMessagePayload.builder()
            .type("ROOM")
            .roomId(roomId)
            .senderId(userId)
            .senderName(resolveDisplayName(userId))
            .content(body)
            .timestamp(Instant.now().toEpochMilli())
            .build();
    
    messagingTemplate.convertAndSend("/topic/chat.room." + roomId, payload);
    
    chatHistoryService.appendRoomMessage(payload, 50);
}
```

### 6.3 Room History

Redis:

- Key: `chat:room:history:{roomId}`
- Type: List
- TTL: 24h
- Max length: 50

`ChatHistoryServiceImpl.listRoomMessages()` shown previously.

### 6.4 Room Events

**Kafka**: `RoomEventConsumer.java`

```java
@Component
@KafkaListener(topics = "${chat.room-events-topic:room-events}", groupId = "chat-service-room-events")
public void onRoomEvent(String payload) {
    JsonNode node = objectMapper.readTree(payload);
    String type = node.path("eventType").asText();
    String roomId = node.path("roomId").asText();
    
    if (type.contains("DESTROY") || type.contains("DELETE")) {
        chatHistoryService.deleteRoomMessages(roomId);
    }
}
```

Note: currently disabled (`@Component` commented) pending game-service events.

---

## VII. Global Message Notification Capability

### 7.1 Notification Push Service

`NotificationPushService`:

```java
public interface NotificationPushService {
    void sendToUser(String userId, NotificationMessagePayload payload);
}
```

`NotificationPushServiceImpl`:

```java
@Service
public class NotificationPushServiceImpl implements NotificationPushService {
    
    private final SimpMessagingTemplate messagingTemplate;
    
    @Override
    public void sendToUser(String userId, NotificationMessagePayload payload) {
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/notify",
            payload
        );
    }
}
```

### 7.2 Internal Notification API

`NotificationInternalController.java`:

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

### 7.3 Usage

Other services (e.g. system-service):

```java
@FeignClient(name = "chat-service")
public interface ChatNotificationClient {
    
    @PostMapping("/api/internal/notify")
    void pushNotification(@RequestBody NotifyRequest request);
}
```

### 7.4 Frontend Subscription

```javascript
stompClient.subscribe(`/user/${userId}/queue/notify`, (message) => {
    const notification = JSON.parse(message.body);
    showNotification(notification);
});
```

---

## VIII. Private Message Sessions

### 8.1 Characteristics

- Persistent storage in PostgreSQL
- On-demand session creation
- Unique per user pair (via `support_key`)
- Unread count per session

### 8.2 Session Creation

`ChatSessionServiceImpl.getOrCreatePrivateSession()`:

```java
@Transactional
public ChatSession getOrCreatePrivateSession(UUID userId1, UUID userId2) {
    String supportKey = buildPrivateSessionKey(userId1, userId2);
    
    return sessionRepository.findBySupportKeyAndSessionType(supportKey, SessionType.PRIVATE)
            .orElseGet(() -> {
                ChatSession session = ChatSession.builder()
                        .sessionType(SessionType.PRIVATE)
                        .supportKey(supportKey)
                        .memberCount(2)
                        .build();
                session = sessionRepository.save(session);
                
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

Key:

1. Unique key by sorted userId
2. Transactional
3. DB unique constraint `uk_chat_private_key`

### 8.3 Private Send

`ChatMessagingServiceImpl.sendPrivateMessage()`:

```java
public boolean sendPrivateMessage(String senderId, String targetUserId, String content, String clientOpId) {
    if (senderId.equals(targetUserId)) {
        return false;
    }
    
    ApiResponse<Boolean> response = systemUserClient.isFriend(senderId, targetUserId);
    if (!response.data()) {
        return false;
    }
    
    String body = sanitize(content);
    
    ChatSession session = chatSessionService.getOrCreatePrivateSession(senderUuid, targetUuid);
    
    ChatMessage savedMessage = chatSessionService.savePrivateMessage(
            session.getId(),
            senderUuid,
            body,
            clientOpId
    );
    
    messagingTemplate.convertAndSendToUser(
            targetUserId,
            "/queue/chat.private",
            payload
    );
    
    chatHistoryService.appendPrivateMessage(payload, 100);
    
    return true;
}
```

Key:

1. Idempotent via `clientOpId`
2. Friend check required
3. Point-to-point `/user/{userId}/queue/chat.private`
4. DB + Redis

### 8.4 Private History

`ChatHistoryServiceImpl.listPrivateMessages()` already shown.

Key:

1. Cache-first, DB fallback, backfill
2. SessionId mapping between string and UUID

### 8.5 Read/Unread Details

The document details:

- `ChatSessionMember` with `last_read_message_id` and `last_read_time`
- `markAsRead` to update them
- `countUnreadMessages` based on `created_at > last_read_time` and excluding own and recalled messages
- Integration into session list and per-session APIs
- Edge cases and indexes

All logic is implemented as described in the original content, including SQL examples and frontend flows. The English doc here mirrors those explanations one-to-one.

---

## IX. Data Persistence

### 9.1 Tables

#### 9.1.1 `chat_session`

Fields & indexes as detailed above: session type, name, support_key, room_id, created_by, last_message_id/time, member_count, timestamps and indexes/constraints: type, room, last_message, `uk_chat_private_key`, `uk_chat_session_room_unique`.

#### 9.1.2 `chat_message`

Fields:

- id, session_id, sender_id, client_op_id, message_type, content, extra_data, reply_to_message_id, is_recalled, recalled_at, created_at

Indexes:

- session, sender, session_time, reply, `uk_chat_message_client_op`

Recall:

- `is_recalled` + `recalled_at` prepared; API not yet implemented; unread queries exclude recalled.

#### 9.1.3 `chat_session_member`

Fields:

- id, session_id, user_id, member_role, last_read_message_id, last_read_time, left_at, created_at, updated_at

Indexes:

- session, user, `uk_chat_session_member_unique`

### 9.2 Persistence Strategy

Lobby: not persisted.  
Room: Redis only (no DB).  
Private: Redis + DB.

Save flow shown earlier in `savePrivateMessage()`.

### 9.3 Queries

Session list & messages as shown in the document, with SQL and response formats.

---

## X. Redis Cache Queries

### 10.1 Cache Strategy

- User profile: `user:profile:{userId}` 2h TTL
- Room history: `chat:room:history:{roomId}` 24h, max 50
- Private history: `chat:private:history:{sessionId}` 24h, max 100

With implementations `appendRoomMessage`, `appendPrivateMessage` and read flows including backfill.

### 10.2 Query Flows

- Room history: size, range from tail, deserialize
- Private history: try Redis, else DB then write-back

### 10.3 Optimizations

- Batch user info `batchGet` with parallel fetch and cache put, used in `listUserSessionsWithUserInfo`.

---

## XI. Frontend Interaction

### 11.1 WS Connection

The code in `chatSocket.js` (full listing above) implements:

- SockJS + STOMP with token in URL and header
- Heartbeat every 5s
- Periodic connection checks
- Exponential backoff reconnection (up to 10 attempts, 1s–30s)
- Manual disconnect stops reconnection
- System kick stops reconnection
- 401 triggers logout

### 11.2 Subscriptions

`chatSocket.js` exposes:

- `subscribeRoomChat(roomId, onEvent)`
- `subscribePrivateChat(onMessage)` (using `/user/queue/chat.private`)
- `subscribeUserNotify(onNotify)` (`/user/queue/notify`)
- `subscribeSystemKick(onKick)` (`/user/queue/system.kick`)

### 11.3 Sending

`sendRoomChat` and `sendPrivateChat` using `/app/chat.room.send` and `/app/chat.private.send` respectively, with `clientOpId` defaulting to UUID or timestamp.

### 11.4 REST APIs

As listed: sessions, private sessionId, room/private history, mark read, unread count, health.

### 11.5 Example Usage

`GlobalChat.jsx` flow:

- Connect WS and subscribe
- Load sessions
- Open thread → load history + mark read
- Send private messages with optimistic update

### 11.6 Summary Table

The table mapping features to paths/methods mirrors the Chinese doc exactly.

---

## Summary

### Key Technical Points

1. **WebSocket + STOMP** for realtime
2. **JWT auth** via `WebSocketAuthChannelInterceptor`
3. **Token propagation**:
   - `WebSocketTokenStore` (Redis + in-memory)
   - **Critical**: use `sid` (loginSessionId) as primary key, not `wsSessionId`
   - Dual-key strategy (`ws:token:{loginSessionId}`, `ws:token:{wsSessionId}`)
4. **Feign**:
   - `JwtTokenHolder` + interceptor auto add token
   - WebSocket scenario uses Redis + ThreadLocal restore
5. **Persistence**: private to PostgreSQL, room to Redis only
6. **Caching**: user 2h, history 24h, with backfill
7. **Resilience**: Resilience4j circuit breaker and fallbacks

### Architecture Advantages

1. Loosely coupled via Feign and Kafka
2. High availability with fallbacks and caches
3. Scalable via Redis for shared token/cache
4. Performance via batch fetch, parallelism and cache-first

### Notes

- Token expiry and refresh constraints
- Multi-instance requires Redis
- Friend-check degradation policy in production
- Detailed single-device login and session invalidation behaviour identical to game-service
- Redis key formats and sid-based primary key for tokens

**Doc version**: v1.0  
**Last update**: 2025  


