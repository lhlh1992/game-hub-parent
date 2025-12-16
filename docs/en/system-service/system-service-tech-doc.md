# System-Service Technical Documentation

> This document details the technical implementation of system-service, including code architecture, feature implementation, configuration notes, data models, API list, frontend interaction, etc.

---

## Table of Contents

1. [Service Overview](#i-service-overview)
2. [Code Architecture](#ii-code-architecture)
3. [Configuration Notes](#iii-configuration-notes)
4. [Core Feature Implementation](#iv-core-feature-implementation)
5. [Data Models](#v-data-models)
6. [OpenFeign Configuration and Usage](#vi-openfeign-configuration-and-usage)
7. [API List](#vii-api-list)
8. [Frontend Interaction](#viii-frontend-interaction)
9. [Scalability Analysis](#ix-scalability-analysis)
10. [Summary](#x-summary)

---

## I. Service Overview

### 1.1 Service Positioning

**system-service** is the **core system-domain service**, responsible for all features related to “user management” and “system functions”, including:

- **User Management**: user sync, create, update, delete, profile management
- **Friend System**: friend request, accept/reject, friend list, friend relationship check
- **Notification System**: global notification create/query, unread count, mark read, metadata management
- **File Storage**: upload/manage avatars, game replays, activity assets
- **Keycloak Integration**: listen registration events, auto-create local user
- **Session Monitoring**: online session query (for dev/debug)
- **RBAC**: role/permission entity definitions (business logic to be completed)

### 1.2 Tech Stack

- **Framework**: Spring Boot 3.x + Spring Data JPA
- **Security**: Spring Security + OAuth2 Resource Server (Keycloak JWT)
- **Storage**: PostgreSQL (JPA/Hibernate) + Redis
- **Service Calls**: Spring Cloud OpenFeign + Resilience4j Circuit Breaker
- **File Storage**: MinIO
- **Identity**: Keycloak Admin Client
- **Service Discovery**: Spring Cloud LoadBalancer
- **Session Mgmt**: session-common (`SessionRegistry`, `sessionRedisTemplate`)

### 1.3 Core Responsibilities

| Module | Responsibility |
|--------|----------------|
| User Management | Keycloak ↔ system DB sync, profile management, user info cache |
| Friend System | Friend request flow, auto-accept mutual requests, maintain relationships |
| Notification System | Create/ persist notifications, Feign push via chat-service, query & state mgmt |
| File Storage | MinIO upload, avatar normalization, temp file mgmt |
| Keycloak Events | Listen register events, auto-create local user, idempotency |
| Session Monitoring | Query online sessions, show user info (dev/debug) |

---

## II. Code Architecture

### 2.1 Package Structure

```
com.gamehub.systemservice
├── SystemServiceApplication          # Bootstrap
├── common/                           # Common classes
│   └── Result.java                   # Unified response
├── config/                           # Config
│   ├── SecurityConfig                # Spring Security + OAuth2
│   ├── KeycloakConfig                # Keycloak Admin Client config
│   ├── MinioConfig                   # MinIO client config
│   └── RestTemplateConfig            # RestTemplate config
├── controller/                       # Controllers
│   ├── AuthController                # Auth (Token obtain)
│   ├── friend/                       # Friend APIs
│   │   └── FriendController
│   ├── notification/                 # Notification APIs
│   │   └── NotificationController
│   ├── user/                         # User APIs
│   │   ├── UserController
│   │   └── UserProfileController
│   ├── file/                         # File APIs
│   │   └── FileController
│   ├── internal/                     # Internal APIs
│   │   └── KeycloakEventController   # Keycloak event callbacks
│   └── SessionMonitorController      # Session monitor
├── service/                          # Services
│   ├── user/                         # User services
│   │   ├── UserService
│   │   ├── UserProfileCacheService   # User info cache
│   │   └── impl/
│   │       └── UserServiceImpl
│   ├── friend/                       # Friend services
│   │   ├── FriendService
│   │   └── impl/
│   │       └── FriendServiceImpl
│   ├── notification/                 # Notification services
│   │   ├── NotificationService
│   │   ├── dto/
│   │   │   ├── NotificationView
│   │   │   └── NotificationTypeMetadata
│   │   └── impl/
│   │       └── NotificationServiceImpl
│   ├── file/                         # File storage services
│   │   ├── FileStorageService
│   │   └── impl/
│   │       └── MinioFileStorageService
│   └── keycloak/                     # Keycloak event services
│       ├── KeycloakEventService
│       └── impl/
│           └── KeycloakEventServiceImpl
├── entity/                           # Entities
│   ├── user/                         # User entities
│   │   ├── SysUser                   # user table
│   │   └── SysUserProfile            # profile table
│   ├── friend/                       # Friend entities
│   │   ├── FriendRequest             # friend_request
│   │   └── UserFriend                # user_friend
│   ├── notification/                 # Notification entity
│   │   └── Notification
│   ├── role/                         # RBAC roles
│   │   ├── SysRole
│   │   ├── SysRolePermission
│   │   ├── SysUserRole
│   │   └── ...
│   └── permission/                   # RBAC permissions
│       └── SysPermission
├── repository/                       # Repositories
│   ├── user/
│   │   ├── SysUserRepository
│   │   └── SysUserProfileRepository
│   ├── friend/
│   │   ├── FriendRequestRepository
│   │   └── UserFriendRepository
│   ├── notification/
│   │   └── NotificationRepository
│   └── role/、permission/           # RBAC repositories
├── dto/                              # DTOs
│   ├── request/                      # Request DTOs
│   │   ├── ApplyFriendRequest
│   │   ├── CreateUserRequest
│   │   ├── LoginRequest
│   │   ├── UpdateProfileRequest
│   │   └── UpdateUserRequest
│   ├── response/                     # Response DTOs
│   │   ├── FriendInfo
│   │   ├── TokenResponse
│   │   ├── UserInfo
│   │   └── UserSessionSnapshotWithUserInfo
│   └── keycloak/
│       └── KeycloakEventPayload
├── infrastructure/                   # Infrastructure
│   └── client/                       # Feign clients
│       └── chat/
│           ├── ChatNotifyClient      # Call chat-service to push notify
│           ├── ChatNotifyClientFallback
│           └── dto/
│               └── NotifyPushRequest
├── exception/                        # Exceptions
│   ├── BusinessException
│   └── GlobalExceptionHandler
└── SystemServiceApplication          # Bootstrap
```

### 2.2 Key Class Responsibilities

| Class | Responsibility |
|-------|----------------|
| `UserServiceImpl` | User sync/create/update/delete, profile mgmt, batch query, Keycloak two-way sync |
| `FriendServiceImpl` | Friend apply/accept/reject, auto-accept mutual, list, relationship check |
| `NotificationServiceImpl` | Notification create/query/unread/read, clear actions, Feign push |
| `MinioFileStorageService` | File upload, avatar normalization, temp file mgmt |
| `KeycloakEventServiceImpl` | Handle Keycloak register events, auto-create local user |
| `UserProfileCacheService` | User info cache (Redis, TTL 2h) |
| `ChatNotifyClient` | Feign client to push notifications |
| `GlobalExceptionHandler` | Global exception handling, unified error response |

---

## III. Configuration Notes

### 3.1 Spring Security Config

**File**: `config/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/users/save").permitAll()
                .requestMatchers("/api/auth/token").permitAll()
                .requestMatchers("/internal/keycloak/events/**").permitAll()
                .requestMatchers("/internal/sessions/**").permitAll()
                .requestMatchers("/api/files/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt());
        return http.build();
    }
}
```

**Notes**:
- Acts as OAuth2 Resource Server, validates JWT from Gateway
- Stateless (JWT, no session)
- Some endpoints are open (register, token, Keycloak events, session monitor, file upload)

### 3.2 Keycloak Admin Client Config

**File**: `config/KeycloakConfig.java`

```java
@Configuration
public class KeycloakConfig {
    @Bean
    public Keycloak keycloakAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")
                .clientId("admin-cli")
                .username(adminUsername)
                .password(adminPassword)
                .build();
    }
}
```

**application.yml**:
```yaml
keycloak:
  server-url: http://127.0.0.1:8180
  realm: my-realm
  admin-username: admin
  admin-password: admin
  token-client-id: game-hub
  token-client-secret: Lt2kkFyWYjJAEBNd7ZAk3TQTjMkj89iZ
  event-webhook-secret: change-me-please
  event-webhook-basic-username: admin
  event-webhook-basic-password: 123456
```

**Notes**:
- Username/password auth (dev)
- Used to create/manage Keycloak users via Admin API
- Keycloak event webhook uses Basic Auth

### 3.3 MinIO Config

**File**: `config/MinioConfig.java`

```java
@Configuration
public class MinioConfig {
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
```

**application.yml**:
```yaml
minio:
  endpoint: http://127.0.0.1:9000
  access-key: NnwqpqOuwiSWkS5J
  secret-key: UN55tffJZvRSAa65lwbYZphMC23TOvWh
  bucket:
    avatars: avatars
    game-replays: game-replays
    materials: materials
    temp: temp
  temp-expire-hours: 24
  public-url-prefix: http://127.0.0.1:9000
```

**Notes**:
- Multiple buckets: avatars / game-replays / materials / temp
- Temp file expire: 24h
- Public URL prefix: direct MinIO (no Nginx)

### 3.4 Datasource Config

**application.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/gamehub_db
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      pool-name: SystemServiceHikariCP
  
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        use_sql_comments: true
```

### 3.5 Redis Config

**application.yml**:
```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: zaqxsw
      database: 3
      timeout: 2s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 0
          max-wait: 5s

# session config (for session monitor)
session:
  redis:
    host: ${SESSION_REDIS_HOST:127.0.0.1}
    port: ${SESSION_REDIS_PORT:6379}
    database: ${SESSION_REDIS_DATABASE:0}
    password: ${SESSION_REDIS_PASSWORD:zaqxsw}
```

**Notes**:
- **Spring Data Redis**: database 3 (reserved)
- **Session Redis**: database 0 (session monitor + user cache)
  - `UserProfileCacheService` uses `sessionRedisTemplate` from `session-common` (`@Qualifier("sessionRedisTemplate")`)
  - `SessionMonitorController` uses `SessionRegistry` (also depends on `sessionRedisTemplate`)
  - `sessionRedisTemplate` auto-configured by `SessionRedisConfig` (session-common) via `session.redis.*`

### 3.6 OpenFeign Config

**application.yml**:
```yaml
spring:
  cloud:
    loadbalancer:
      enabled: true
    discovery:
      client:
        simple:
          instances:
            chat-service:
              - uri: http://127.0.0.1:8083
                metadata:
                  secure: false
```

**Notes**:
- Local dev without registry; uses Simple Discovery to point chat-service
- Avoid LoadBalancer 503

---

## IV. Core Feature Implementation

### 4.1 User Management

#### 4.1.1 User Sync (Keycloak ↔ System-Service)

**Location**: `service/user/impl/UserServiceImpl.syncUser()`

**Flow**:
1. Query system user by Keycloak userId
2. If exists, update username/email
3. If not, create user + profile

**Code**:
```java
@Transactional
public SysUser syncUser(UUID keycloakUserId, String username, String email) {
    Optional<SysUser> existingUser = userRepository.findByKeycloakUserIdAndNotDeleted(keycloakUserId);
    
    if (existingUser.isPresent()) {
        SysUser user = existingUser.get();
        if (username != null && !username.equals(user.getUsername())) {
            if (userRepository.existsByUsernameAndNotDeleted(username) && 
                !username.equals(user.getUsername())) {
                throw new BusinessException("用户名已被使用: " + username);
            }
            user.setUsername(username);
        }
        if (email != null) {
            user.setEmail(email);
        }
        return userRepository.save(user);
    } else {
        if (userRepository.existsByUsernameAndNotDeleted(username)) {
            throw new BusinessException("用户名已被使用: " + username);
        }
        
        SysUser user = SysUser.builder()
                .keycloakUserId(keycloakUserId)
                .username(username)
                .email(email)
                .playerId(generatePlayerIdFromSequence())
                .userType(SysUser.UserType.NORMAL)
                .status(1)
                .build();
        user = userRepository.save(user);
        
        SysUserProfile profile = SysUserProfile.builder()
                .userId(user.getId())
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .settings(Map.of())
                .build();
        userProfileRepository.save(profile);
        
        return user;
    }
}
```

**Key Points**:
- Use DB sequence for global unique `playerId`
- Auto-create `SysUserProfile`
- Username uniqueness check

#### 4.1.2 User Create (Keycloak + System-Service)

**Location**: `service/user/impl/UserServiceImpl.createUser()`

**Flow**:
1. Check username exists in system DB
2. Check username exists in Keycloak
3. Create user in Keycloak
4. Set Keycloak password
5. Create user + profile in system DB
6. On any failure, rollback created resources

**Key Points**:
- Transactional consistency between Keycloak and DB
- Rollback: if DB create fails, delete Keycloak user
- If password set fails, delete Keycloak user

#### 4.1.3 User Update (Keycloak + System-Service Two-Way)

**Location**: `service/user/impl/UserServiceImpl.updateUser()`

**Sync Fields**:
- **Nickname**: Keycloak `firstName` + DB `nickname`
- **Email**: Keycloak `email` + DB `email`
- **Password**: only Keycloak (DB not store)

**Code**:
```java
@Transactional
public SysUser updateUser(UUID userId, String nickname, String password, String email) {
    SysUser user = userRepository.findById(userId)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new BusinessException("用户不存在或已被删除"));
    
    // nickname sync Keycloak
    if (nickname != null && !nickname.isBlank()) {
        user.setNickname(nickname);
        RealmResource realmResource = keycloak.realm(realm);
        UserRepresentation keycloakUser = realmResource.users()
                .get(user.getKeycloakUserId().toString())
                .toRepresentation();
        keycloakUser.setFirstName(nickname);
        realmResource.users().get(user.getKeycloakUserId().toString()).update(keycloakUser);
    }
    
    // email sync Keycloak
    if (email != null && !email.isBlank()) {
        Optional<SysUser> existingUser = userRepository.findByEmailAndNotDeleted(email);
        if (existingUser.isPresent() && !existingUser.get().getId().equals(userId)) {
            throw new BusinessException("邮箱已被其他用户使用: " + email);
        }
        user.setEmail(email);
        // sync Keycloak ...
    }
    
    // password: only Keycloak
    if (password != null && !password.isBlank()) {
        // update in Keycloak only
    }
    
    return userRepository.save(user);
}
```

#### 4.1.4 User Delete (Soft Delete)

**Location**: `service/user/impl/UserServiceImpl.deleteUser()`

**Flow**:
1. Disable user in Keycloak (not delete)
2. Soft delete + disable in system DB (`deletedAt`, `status=0`)
3. Clear user cache

**Key Points**:
- Soft delete keeps data for recovery/audit
- Keycloak disable failure does not block DB soft delete (tolerant)

#### 4.1.5 User Info Query (Batch, Cached)

**Location**: `service/user/impl/UserServiceImpl.findUserInfosByKeycloakUserIds()`

**Flow**:
1. Try Redis cache (`UserProfileCacheService`)
2. Miss → batch DB query (`sys_user` + `sys_user_profile`)
3. Assemble `UserInfo` DTO
4. Write cache (TTL 2h)
5. Return in request order

**Cache**:
- Key: `user:profile:{keycloakUserId}`
- TTL: 2h
- Redis: uses `sessionRedisTemplate` from `session-common` (db 0, shared)
- Serialization: JSON manually (ObjectMapper)
- Refresh on update; evict on delete

**Code**:
```java
public List<UserInfo> findUserInfosByKeycloakUserIds(List<String> keycloakUserIds) {
    Map<String, UserInfo> hitCache = new LinkedHashMap<>();
    List<String> misses = new ArrayList<>();
    for (String id : keycloakUserIds) {
        userProfileCacheService.get(id).ifPresentOrElse(
                info -> hitCache.put(id, info),
                () -> misses.add(id)
        );
    }
    
    Map<String, UserInfo> dbResultMap = new LinkedHashMap<>();
    if (!misses.isEmpty()) {
        List<SysUser> users = userRepository.findByKeycloakUserIdInAndNotDeleted(uuidList);
        // query profile, assemble UserInfo, cache ...
        userProfileCacheService.put(info);
    }
    
    // return in original order
    // ...
}
```

#### 4.1.6 Profile Update

**Location**: `service/user/impl/UserServiceImpl.updateProfile()`

**Flow**:
1. Handle avatar (temp URL → official path, normalize filename)
2. Update `sys_user` (nickname/email/phone/avatar, sync Keycloak)
3. Update or create `sys_user_profile` (bio/locale/timezone/settings)
4. Refresh cache

**Avatar Handling**:
- Temp URL `/temp/`: move to `avatars`, rename `{userId}.{ext}`
- Already in avatars: normalize filename `{userId}.{ext}`
- Others: use as is

**Code**:
```java
@Transactional
public UserInfo updateProfile(String keycloakUserId, String nickname, String email, String phone,
                              String avatarUrl, String bio, String locale, String timezone,
                              Map<String, Object> settings) {
    UUID keycloakUserIdUuid = UUID.fromString(keycloakUserId);
    SysUser user = userRepository.findByKeycloakUserIdAndNotDeleted(keycloakUserIdUuid)
            .orElseThrow(() -> new BusinessException("用户不存在"));
    
    String finalAvatarUrl = null;
    String avatarNameId = user.getKeycloakUserId() != null ? user.getKeycloakUserId().toString() : user.getId().toString();
    if (avatarUrl != null && !avatarUrl.isBlank()) {
        if (avatarUrl.contains("/temp/")) {
            finalAvatarUrl = fileStorageService.moveAvatarFromTemp(avatarUrl, avatarNameId);
        } else if (avatarUrl.contains("/avatars/")) {
            finalAvatarUrl = fileStorageService.normalizeAvatarInAvatars(avatarUrl, avatarNameId);
        } else {
            finalAvatarUrl = avatarUrl;
        }
    }
    
    // update sys_user (sync Keycloak) ...
    // update/create sys_user_profile ...
    // refresh cache
    userProfileCacheService.put(updated);
    return updated;
}
```

### 4.2 Friend System

#### 4.2.1 Friend Apply

**Location**: `service/friend/impl/FriendServiceImpl.applyFriend()`

**Flow**:
1. Validate: cannot add self
2. Normalize message: trim, empty → null, max 200 chars
3. Convert Keycloak userId to UUID
4. Query system userIds of requester/target
5. Check already friends
6. Check pending request exists
7. **Check reverse pending request (auto-accept mutual)**
8. If no reverse, create request and push notify

**Mutual Auto-Accept**:
```java
Optional<FriendRequest> reverseRequest = friendRequestRepository.findReversePendingRequest(requesterId, targetId);

if (reverseRequest.isPresent()) {
    return handleMutualRequest(requesterId, targetId, reverseRequest.get(), normalizedMessage);
} else {
    FriendRequest created = createFriendRequest(requesterId, targetId, normalizedMessage);
    notificationService.notifyFriendRequest(...);
    return false;
}
```

`handleMutualRequest()`:
1. Mark reverse request `ACCEPTED`
2. Create forward request as `ACCEPTED`
3. Create two friend relations (both directions)
4. Return `true` (auto-friend)

**Key Points**:
- Message length limit 200
- Mutual auto-accept improves UX
- Transaction: requests + relations in one TX

#### 4.2.2 Accept/Reject Friend Request

**Location**: `service/friend/impl/FriendServiceImpl.handleFriendRequest()`

**Flow**:
1. Validate: current user must be receiver, status `PENDING`
2. Update status: `ACCEPTED` or `REJECTED`
3. If accept, create two `UserFriend`
4. Clear receiver notification actions
5. Notify requester result (`FRIEND_RESULT`)

**Code**:
```java
private void handleFriendRequest(String receiverKeycloakUserId, UUID requestId, boolean accept) {
    FriendRequest request = friendRequestRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException(404, "好友申请不存在"));
    
    if (!request.getReceiverId().equals(getSystemUserId(receiverUuid))) {
        throw new BusinessException(403, "无权处理该申请");
    }
    if (request.getStatus() != FriendRequest.RequestStatus.PENDING) {
        throw new BusinessException(409, "该申请已处理");
    }
    
    OffsetDateTime now = OffsetDateTime.now();
    request.setStatus(accept ? FriendRequest.RequestStatus.ACCEPTED : FriendRequest.RequestStatus.REJECTED);
    request.setHandledAt(now);
    friendRequestRepository.save(request);
    
    if (accept) {
        createFriendRelation(request.getRequesterId(), request.getReceiverId(), now);
    }
    
    notificationService.clearNotificationActions(receiverSystemUserId, "FRIEND_REQUEST", requestId);
    
    notifyRequesterResult(request, accept);
}
```

**Key Points**:
- Auth: only receiver
- State: must be `PENDING`
- Two `UserFriend` records
- Clear notification actions after handled

#### 4.2.3 Friend List Query

**Location**: `service/friend/impl/FriendServiceImpl.getFriendsList()`

**Flow**:
1. Convert Keycloak userId to UUID
2. Query system userId
3. Query all `ACTIVE` friend relations
4. Batch query friend system users
5. Batch query friend `UserInfo`
6. Assemble `FriendInfo`

**Code**:
```java
public List<FriendInfo> getFriendsList(String currentUserKeycloakUserId) {
    UUID currentUserKeycloakUuid = UUID.fromString(currentUserKeycloakUserId);
    UUID currentSystemUserId = getSystemUserId(currentUserKeycloakUuid);
    
    List<UserFriend> friendRelations = userFriendRepository.findActiveFriendsByUserId(currentSystemUserId);
    
    List<UUID> friendSystemUserIds = friendRelations.stream()
            .map(UserFriend::getFriendId)
            .distinct()
            .collect(Collectors.toList());
    
    List<SysUser> friendSysUsers = sysUserRepository.findAllById(friendSystemUserIds);
    
    List<String> friendKeycloakUserIds = friendSysUsers.stream()
            .map(user -> user.getKeycloakUserId().toString())
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    
    List<UserInfo> friendUserInfos = userService.findUserInfosByKeycloakUserIds(friendKeycloakUserIds);
    
    // assemble FriendInfo ...
}
```

**Key Points**:
- Batch queries reduce DB hits
- Reuse `UserInfo` with cache

#### 4.2.4 Friend Check

**Location**: `service/friend/impl/FriendServiceImpl.isFriend()`

**Use**: Pre-check for private chat, etc.

**Flow**:
1. Validate params
2. Convert Keycloak IDs to UUID
3. Query system userIds
4. Check relationship `ACTIVE`

### 4.3 Notification System

#### 4.3.1 Notification Create (Friend Request)

**Location**: `service/notification/impl/NotificationServiceImpl.notifyFriendRequest()`

**Flow**:
1. Persist `Notification` (`sys_notification`)
2. Push via `ChatNotifyClient.push()` WebSocket
3. Push failure does not affect main TX (warn log only)

**Code**:
```java
@Transactional(rollbackFor = Exception.class)
public void notifyFriendRequest(UUID receiverUserId,
                                String receiverKeycloakUserId,
                                String requesterKeycloakUserId,
                                String requesterName,
                                UUID friendRequestId,
                                String requestMessage) {
    Notification notification = Notification.builder()
            .userId(receiverUserId)
            .type("FRIEND_REQUEST")
            .title("好友申请")
            .content(requesterName + " 请求加你为好友")
            .fromUserId(requesterKeycloakUserId)
            .refType("FRIEND_REQUEST")
            .refId(friendRequestId)
            .payload(buildPayload(friendRequestId, requestMessage, requesterName, null))
            .actions(List.of("ACCEPT", "REJECT"))
            .status("UNREAD")
            .sourceService("system-service")
            .build();
    notificationRepository.save(notification);
    
    try {
        pushToChatService(receiverKeycloakUserId, requesterKeycloakUserId, requesterName, 
                         friendRequestId, requestMessage, notification.getId());
    } catch (Exception e) {
        log.warn("推送好友申请通知失败（已落库，用户可离线查看）：receiver={}, refId={}, err={}",
                receiverKeycloakUserId, friendRequestId, e.getMessage());
    }
}
```

**Key Points**:
- Persist first, then push (offline visible)
- Push failure does not break TX
- Carry `notificationId` for dedup/read marking

#### 4.3.2 Notification Create (Friend Result)

**Location**: `service/notification/impl/NotificationServiceImpl.notifyFriendResult()`

**Flow**:
1. Persist `Notification` (type `FRIEND_RESULT`)
2. Push via `ChatNotifyClient.push()`
3. Push failure tolerated

**Key Points**:
- Inform requester result (accept/reject)
- `actions` empty (no action)

#### 4.3.3 Notification Query

**Location**: `service/notification/impl/NotificationServiceImpl.listNotifications()`

**Ordering**:
- Unread first: `CASE WHEN n.status = 'UNREAD' THEN 0 ELSE 1 END`
- Time desc: `n.createdAt DESC`

**Code**:
```java
@Query("""
    SELECT n FROM Notification n
    WHERE n.userId = :userId
      AND (:status IS NULL OR n.status = :status)
    ORDER BY 
      CASE WHEN n.status = 'UNREAD' THEN 0 ELSE 1 END,
      n.createdAt DESC
    """)
List<Notification> findByUserIdAndStatus(@Param("userId") UUID userId,
                                         @Param("status") String status,
                                         Pageable pageable);
```

**Key Points**:
- Status filter
- Unread-first sort improves UX
- Pagination (`limit` max 100)

#### 4.3.4 Unread Count

**Location**: `service/notification/impl/NotificationServiceImpl.countUnread()`

```java
@Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.status = 'UNREAD'")
long countUnread(@Param("userId") UUID userId);
```

#### 4.3.5 Mark Read

**Location**: `service/notification/impl/NotificationServiceImpl.markRead()`

**Flow**:
1. Query notification
2. Auth: userId must match
3. Set status `READ`, set `readAt`

**Batch**:
- Up to 500 to avoid huge update

#### 4.3.6 Clear Notification Actions

**Location**: `service/notification/impl/NotificationServiceImpl.clearNotificationActions()`

**Use**: When business completed (e.g., friend request handled), remove buttons

**Flow**:
1. Find by `userId` + `refType` + `refId`
2. Set `actions=null`
3. If status UNREAD, mark READ

**Code**:
```java
@Transactional
public void clearNotificationActions(UUID userId, String refType, UUID refId) {
    List<Notification> notifications = notificationRepository.findByUserIdAndRefTypeAndRefId(userId, refType, refId);
    
    notifications.forEach(n -> {
        n.setActions(null);
        if ("UNREAD".equalsIgnoreCase(n.getStatus())) {
            n.setStatus("READ");
            n.setReadAt(OffsetDateTime.now());
        }
    });
    notificationRepository.saveAll(notifications);
}
```

#### 4.3.7 Notification Status Query (Handled Friend Request)

**Location**: `service/notification/impl/NotificationServiceImpl.toView()`

**Use**: For handled friend requests (`actions` empty), query status and add to payload

**Code**:
```java
private NotificationView toView(Notification n) {
    Map<String, Object> payload = n.getPayload() != null ? new HashMap<>(n.getPayload()) : new HashMap<>();
    
    if ("FRIEND_REQUEST".equals(n.getType()) 
        && (n.getActions() == null || n.getActions().isEmpty()) 
        && n.getRefId() != null) {
        try {
            friendRequestRepository.findById(n.getRefId()).ifPresent(request -> {
                FriendRequest.RequestStatus requestStatus = request.getStatus();
                if (requestStatus == FriendRequest.RequestStatus.ACCEPTED) {
                    payload.put("handledStatus", "ACCEPTED");
                    payload.put("handledStatusText", "已同意");
                } else if (requestStatus == FriendRequest.RequestStatus.REJECTED) {
                    payload.put("handledStatus", "REJECTED");
                    payload.put("handledStatusText", "已拒绝");
                }
            });
        } catch (Exception e) {
            log.debug("查询好友申请状态失败: notificationId={}, refId={}, err={}", 
                n.getId(), n.getRefId(), e.getMessage());
        }
    }
    
    return NotificationView.builder()
            .id(n.getId())
            .type(n.getType())
            .title(n.getTitle())
            .content(n.getContent())
            .status(n.getStatus())
            .fromUserId(n.getFromUserId())
            .refType(n.getRefType())
            .refId(n.getRefId())
            .payload(payload)
            .actions(n.getActions())
            .sourceService(n.getSourceService())
            .createdAt(n.getCreatedAt())
            .readAt(n.getReadAt())
            .build();
}
```

**Key Points**:
- Dynamically show handled status for friend requests
- Swallow errors, do not affect display

#### 4.3.8 Notification Metadata

**Location**: `controller/notification/NotificationController.metadata()`

**Use**: Return supported notification type metadata for frontend

**Code**:
```java
@GetMapping("/metadata")
public ResponseEntity<ApiResponse<List<NotificationTypeMetadata>>> metadata() {
    List<NotificationTypeMetadata> list = List.of(
            NotificationTypeMetadata.builder()
                    .type("FRIEND_REQUEST")
                    .actionable(true)
                    .actions(List.of("ACCEPT", "REJECT"))
                    .description("好友申请，可同意/拒绝")
                    .build(),
            NotificationTypeMetadata.builder()
                    .type("FRIEND_RESULT")
                    .actionable(false)
                    .actions(List.of())
                    .description("好友申请结果通知（同意/拒绝），无需操作")
                    .build(),
            NotificationTypeMetadata.builder()
                    .type("SYSTEM_ALERT")
                    .actionable(false)
                    .actions(List.of())
                    .description("系统类通知，仅提示")
                    .build()
    );
    return ResponseEntity.ok(ApiResponse.success(list));
}
```

**Notes**:
- Actual actions still handled by frontend; backend not driving frontend too much

### 4.4 File Storage

#### 4.4.1 Avatar Upload (Temp)

**Location**: `service/file/impl/MinioFileStorageService.uploadAvatarToTemp()`

**Flow**:
1. Validate file (type/size)
2. Filename `{userId}.{ext}`
3. Upload to temp bucket `temp`
4. Return temp URL

**Validation**:
- Types: JPG/JPEG/PNG/GIF/WEBP only
- Size: max 2MB
- Double-check Content-Type and extension

#### 4.4.2 Avatar Normalize

**Location**: `service/file/impl/MinioFileStorageService.normalizeAvatarInAvatars()`

**Use**: If already in `avatars` but name not `userId`, rename to `userId.xxx`

**Flow**:
1. Extract object name
2. If already userId-named, return
3. Copy/overwrite to `userId.xxx`
4. Delete old (non-blocking)

#### 4.4.3 Move Avatar from Temp to Avatars

**Location**: `service/file/impl/MinioFileStorageService.moveAvatarFromTemp()`

**Flow**:
1. Extract temp filename from URL
2. Check exists
3. Copy temp → avatars (overwrite same name)
4. Delete temp
5. Return final URL

**Key**:
- Naming `{userId}.{ext}`
- Delete temp to save space

#### 4.4.4 Game Replay Upload

**Location**: `service/file/impl/MinioFileStorageService.uploadGameReplay()`

**Path**: `{gameType}/{roomId}/replay.json`

#### 4.4.5 Material Upload

**Location**: `service/file/impl/MinioFileStorageService.uploadMaterial()`

**Path**: `{type}/{id}/{originalFilename}`

### 4.5 Keycloak Event Handling

#### 4.5.1 Keycloak Event Callback

**Location**: `controller/internal/KeycloakEventController.onEvent()`

**Auth**: Basic Auth (`event-webhook-basic-username` + `event-webhook-basic-password`)

**Event Formats**:
- **vymalo/keycloak-webhook direct**: `{"type": "REGISTER", "userId": "...", "details": {...}}`
- **Wrapper**: `{"event": {...}}`

**Code**:
```java
@PostMapping({"/events", "/events/**"})
public ResponseEntity<Result<Void>> onEvent(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody(required = false) String body) {
    
    // Basic Auth ...
    
    KeycloakEventPayload payload = null;
    if (body != null && !body.isBlank()) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            KeycloakEventPayload.Event directEvent = mapper.readValue(body, KeycloakEventPayload.Event.class);
            if (directEvent != null && directEvent.getType() != null) {
                payload = new KeycloakEventPayload();
                payload.setEvent(directEvent);
            }
        } catch (Exception e1) {
            payload = mapper.readValue(body, KeycloakEventPayload.class);
        }
    }
    
    try {
        if (payload != null) {
            eventService.handleEvent(payload);
        }
    } catch (Exception ex) {
        log.error("处理 Keycloak 事件失败（已忽略返回 500）", ex);
    }
    return ResponseEntity.ok(Result.success("ok", null));
}
```

**Key Points**:
- Do not throw 500 on business failure to avoid affecting Keycloak
- Compatible with multiple event formats

#### 4.5.2 User Register Event

**Location**: `service/keycloak/impl/KeycloakEventServiceImpl.handleEvent()`

**Flow**:
1. Handle only `REGISTER`
2. Check existence (idempotent)
3. Prefer event `details` (avoid extra Admin API)
4. If incomplete, call Keycloak Admin API
5. Generate playerId (sequence)
6. Create local user + profile

**Code**:
```java
@Transactional
public void handleEvent(KeycloakEventPayload payload) {
    if (payload == null || payload.getEvent() == null) {
        return;
    }
    
    KeycloakEventPayload.Event event = payload.getEvent();
    String type = event.getType();
    String userId = event.getUserId();
    
    if ("REGISTER".equalsIgnoreCase(type)) {
        createLocalUser(event);
    }
}

private void createLocalUser(KeycloakEventPayload.Event event) {
    String keycloakUserId = event.getUserId();
    UUID kcUserUuid = UUID.fromString(keycloakUserId);
    
    Optional<SysUser> existed = userRepository.findByKeycloakUserIdAndNotDeleted(kcUserUuid);
    if (existed.isPresent()) {
        log.info("用户已存在，跳过创建: keycloakUserId={}", keycloakUserId);
        return;
    }
    
    String username = null;
    String email = null;
    String nickname = null;
    
    if (event.getDetails() != null) {
        username = event.getDetails().get("username");
        email = event.getDetails().get("email");
        String firstName = event.getDetails().get("firstName");
        if (firstName == null || firstName.isBlank()) firstName = event.getDetails().get("first_name");
        if (firstName == null || firstName.isBlank()) firstName = event.getDetails().get("firstname");
        nickname = firstName;
    }
    
    if (needUsername || needEmail || needNickname) {
        Thread.sleep(200);
        UserRepresentation kcUser = keycloak.realm(realm).users().get(keycloakUserId).toRepresentation();
        // fetch missing info
    }
    
    Long playerId = generatePlayerIdFromSequence();
    
    SysUser user = SysUser.builder()
            .keycloakUserId(kcUserUuid)
            .username(username)
            .email(email != null && !email.isBlank() ? email : username)
            .nickname(nickname != null && !nickname.isBlank() ? nickname : username)
            .playerId(playerId)
            .userType(SysUser.UserType.NORMAL)
            .status(1)
            .build();
    user = userRepository.save(user);
    
    SysUserProfile profile = SysUserProfile.builder()
            .userId(user.getId())
            .locale("zh-CN")
            .timezone("Asia/Shanghai")
            .settings(Map.of())
            .build();
    userProfileRepository.save(profile);
}
```

**Key Points**:
- Idempotent: skip if exists
- Prefer event details to reduce Admin API
- 200ms delay to ensure Keycloak persisted data
- Compatible field names

### 4.6 Session Monitoring

**Location**: `controller/SessionMonitorController.getAllSessions()`

**Use**: Dev/debug; view all online sessions

**Flow**:
1. Get sessions from `SessionRegistry`
2. Batch query user info and fill nickname
3. Return session snapshots with user info

**Notes**:
- Fully open; for dev env only
- Belongs in system-service for boundary clarity

---

## V. Data Models

### 5.1 User Tables

#### 5.1.1 sys_user

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | PK (system userId) |
| `keycloak_user_id` | UUID | Keycloak userId (JWT sub, unique) |
| `username` | VARCHAR(50) | Username (case-insensitive, unique) |
| `nickname` | VARCHAR(50) | Nickname |
| `email` | VARCHAR(100) | Email (case-insensitive) |
| `phone` | VARCHAR(20) | Phone |
| `avatar_url` | VARCHAR(500) | Avatar URL |
| `user_type` | VARCHAR(20) | User type (NORMAL/ADMIN) |
| `dept_id` | UUID | Dept ID |
| `status` | INTEGER | 0-disabled, 1-enabled |
| `remark` | VARCHAR(500) | Remark |
| `player_id` | BIGINT | Player ID (unique numeric for lookup/share) |
| `created_at` | TIMESTAMP | Created time |
| `updated_at` | TIMESTAMP | Updated time |
| `deleted_at` | TIMESTAMP | Soft delete (NULL = active) |

**Indexes**:
- `keycloak_user_id` unique
- `username` unique (filter deleted)
- `player_id` unique

#### 5.1.2 sys_user_profile

| Field | Type | Description |
|-------|------|-------------|
| `user_id` | UUID | PK (links sys_user.id) |
| `bio` | VARCHAR(500) | Bio |
| `locale` | VARCHAR(10) | Locale (e.g., zh-CN, en-US) |
| `timezone` | VARCHAR(50) | Timezone (e.g., Asia/Shanghai, UTC) |
| `settings` | JSONB | Settings |
| `created_at` | TIMESTAMP | Created |
| `updated_at` | TIMESTAMP | Updated |

### 5.2 Friend Tables

#### 5.2.1 friend_request

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | PK |
| `requester_id` | UUID | Requester system userId |
| `receiver_id` | UUID | Receiver system userId |
| `request_message` | VARCHAR(200) | Message |
| `status` | VARCHAR(20) | PENDING/ACCEPTED/REJECTED/EXPIRED |
| `handled_at` | TIMESTAMP | Handled time |
| `created_at` | TIMESTAMP | Created |

**Index**:
- `(requester_id, receiver_id, status)` for pending query

#### 5.2.2 user_friend

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | PK |
| `user_id` | UUID | User system ID |
| `friend_id` | UUID | Friend system ID |
| `friend_nickname` | VARCHAR(50) | Remark |
| `friend_group` | VARCHAR(50) | Group |
| `status` | VARCHAR(20) | ACTIVE/BLOCKED |
| `is_favorite` | BOOLEAN | Favorite |
| `last_interaction_time` | TIMESTAMP | Last interaction |
| `created_at` | TIMESTAMP | Created |
| `updated_at` | TIMESTAMP | Updated |

**Unique**:
- `(user_id, friend_id)`

### 5.3 Notification Table

#### 5.3.1 sys_notification

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | PK |
| `user_id` | UUID | Target user (system userId) |
| `type` | VARCHAR(50) | Type (FRIEND_REQUEST, FRIEND_RESULT, SYSTEM_ALERT, etc.) |
| `title` | VARCHAR(200) | Title |
| `content` | VARCHAR(500) | Content |
| `from_user_id` | VARCHAR(64) | Trigger user (Keycloak userId, optional) |
| `ref_type` | VARCHAR(50) | Ref business type (e.g., FRIEND_REQUEST) |
| `ref_id` | UUID | Ref business ID |
| `payload` | JSONB | Payload |
| `actions` | TEXT[] | Action buttons (["ACCEPT","REJECT"]) |
| `status` | VARCHAR(20) | UNREAD/READ/ARCHIVED/DELETED |
| `source_service` | VARCHAR(50) | Source service |
| `created_at` | TIMESTAMP | Created |
| `read_at` | TIMESTAMP | Read at |
| `archived_at` | TIMESTAMP | Archived |
| `deleted_at` | TIMESTAMP | Deleted |
| `updated_at` | TIMESTAMP | Updated |

**Indexes**:
- `(user_id, status)` for query/sort
- `(user_id, ref_type, ref_id)` for clearing actions

### 5.4 RBAC Tables (entities defined, logic TBD)

#### 5.4.1 sys_role

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | PK |
| `role_code` | VARCHAR(50) | Code (unique) |
| `role_name` | VARCHAR(50) | Name |
| `role_desc` | VARCHAR(200) | Description |
| `data_scope` | VARCHAR(20) | ALL/DEPT/DEPT_AND_CHILD/SELF |
| `sort_order` | INTEGER | Sort |
| `status` | INTEGER | 0-disabled, 1-enabled |
| `remark` | VARCHAR(500) | Remark |
| `created_at` | TIMESTAMP | Created |
| `updated_at` | TIMESTAMP | Updated |
| `deleted_at` | TIMESTAMP | Soft delete |

#### 5.4.2 sys_permission

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | PK |
| `permission_code` | VARCHAR(100) | Code (unique) |
| `permission_name` | VARCHAR(100) | Name |
| `permission_type` | VARCHAR(20) | MENU/BUTTON/API |
| `resource_type` | VARCHAR(50) | Resource type |
| `resource_path` | VARCHAR(500) | Resource path |
| `http_method` | VARCHAR(10) | HTTP method |
| `data_expr` | JSONB | Data expression |
| `sort_order` | INTEGER | Sort |
| `status` | INTEGER | 0/1 |
| `remark` | VARCHAR(500) | Remark |
| `created_at` | TIMESTAMP | Created |
| `updated_at` | TIMESTAMP | Updated |
| `deleted_at` | TIMESTAMP | Soft delete |

#### 5.4.3 sys_user_role

| Field | Type | Description |
|-------|------|-------------|
| `user_id` | UUID | User ID (composite PK) |
| `role_id` | UUID | Role ID (composite PK) |
| `created_at` | TIMESTAMP | Created |

**Composite PK**: `(user_id, role_id)` via `@IdClass(SysUserRoleId.class)`

#### 5.4.4 sys_role_permission

| Field | Type | Description |
|-------|------|-------------|
| `role_id` | UUID | Role ID (composite PK) |
| `permission_id` | UUID | Permission ID (composite PK) |
| `created_at` | TIMESTAMP | Created |

**Composite PK**: `(role_id, permission_id)` via `@IdClass(SysRolePermissionId.class)`

---

## VI. OpenFeign Configuration and Usage

### 6.1 Feign Client Definition

**File**: `infrastructure/client/chat/ChatNotifyClient.java`

```java
@FeignClient(name = "chat-service", path = "/api/internal/notify", fallback = ChatNotifyClientFallback.class)
public interface ChatNotifyClient {
    @PostMapping
    @CircuitBreaker(name = "chatNotifyClient")
    void push(@RequestBody NotifyPushRequest request);
}
```

**Notes**:
- Service: `chat-service`
- Path: `/api/internal/notify`
- Circuit breaker: `@CircuitBreaker(name = "chatNotifyClient")`
- Fallback: `ChatNotifyClientFallback` (log only)

### 6.2 Fallback

**File**: `infrastructure/client/chat/ChatNotifyClientFallback.java`

```java
@Component
@Slf4j
public class ChatNotifyClientFallback implements ChatNotifyClient {
    @Override
    public void push(NotifyPushRequest request) {
        log.warn("chat-service notify push fallback: skip push, request={}", request);
    }
}
```

**Notes**:
- On push failure, warn log only, no exception
- Notification already stored; offline visible; main flow unaffected

### 6.3 Request DTO

**File**: `infrastructure/client/chat/dto/NotifyPushRequest.java`

```java
@Data
public class NotifyPushRequest {
    private String userId;          // Target user (Keycloak userId/sub)
    private String type;            // Type: FRIEND_REQUEST, FRIEND_RESULT, etc.
    private String title;           // Title
    private String content;         // Content
    private String fromUserId;      // Trigger user (optional)
    private Object payload;         // Payload
    private String[] actions;       // Action buttons (optional)
}
```

### 6.4 Usage Scenario

**Call Site**: `service/notification/impl/NotificationServiceImpl.pushToChatService()`

```java
private void pushToChatService(String receiverKeycloakUserId,
                               String requesterKeycloakUserId,
                               String requesterName,
                               UUID friendRequestId,
                               String requestMessage,
                               UUID notificationId) {
    NotifyPushRequest body = new NotifyPushRequest();
    body.setUserId(receiverKeycloakUserId);
    body.setType("FRIEND_REQUEST");
    body.setTitle("好友申请");
    body.setContent(requesterName + " 请求加你为好友");
    body.setFromUserId(requesterKeycloakUserId);
    body.setPayload(buildPayload(friendRequestId, requestMessage, requesterName, notificationId));
    body.setActions(new String[]{"ACCEPT", "REJECT"});
    
    chatNotifyClient.push(body);
}
```

**Key Points**:
- Persist first, then push
- Push failure tolerated
- Carry `notificationId` for dedup/read

---

## VII. API List

### 7.1 User APIs

#### 7.1.1 Get Current User Basic
- **Path**: `GET /api/users/me`
- **Desc**: Current user basic info (`sys_user`)
- **Auth**: JWT
- **Resp**: `SysUser`

#### 7.1.2 Get Current User Full
- **Path**: `GET /api/users/me/full`
- **Desc**: Full info (`sys_user` + `sys_user_profile` + reserved game info)
- **Auth**: JWT
- **Resp**: `UserInfo`

#### 7.1.3 Sync User
- **Path**: `POST /api/users/sync`
- **Desc**: Sync from Keycloak to system (usually first login)
- **Auth**: JWT
- **Req**: info from JWT
- **Resp**: `SysUser`

#### 7.1.4 Create User
- **Path**: `POST /api/users/save`
- **Desc**: Create in Keycloak and system
- **Auth**: None (register)
- **Req**: `CreateUserRequest` (username, nickname, password, email)
- **Resp**: `SysUser`

#### 7.1.5 Update User
- **Path**: `PUT /api/users/update/{userId}`
- **Desc**: Update user (nickname, password, email), sync Keycloak
- **Auth**: JWT
- **Req**: `UpdateUserRequest` (nickname, password, email)
- **Resp**: `SysUser`

#### 7.1.6 Delete User
- **Path**: `DELETE /api/users/delete/{userId}`
- **Desc**: Soft delete user (disable Keycloak user, soft delete system)
- **Auth**: JWT
- **Resp**: `Result<Void>`

#### 7.1.7 Batch Get Users
- **Path**: `POST /api/users/users/batch`
- **Desc**: Batch by Keycloak userId list
- **Auth**: JWT
- **Req**: `List<String>` (Keycloak userIds)
- **Resp**: `List<UserInfo>`

#### 7.1.8 Get User by Keycloak ID
- **Path**: `GET /api/users/users/{userId}`
- **Desc**: Get by single Keycloak userId
- **Auth**: JWT
- **Resp**: `UserInfo`

#### 7.1.9 Get Current Profile
- **Path**: `GET /api/users/me/profile`
- **Desc**: Current user full profile (`UserInfo`)
- **Auth**: JWT
- **Resp**: `UserInfo`

#### 7.1.10 Update Current Profile
- **Path**: `PUT /api/users/me/profile`
- **Desc**: Update current profile (`sys_user` + `sys_user_profile`)
- **Auth**: JWT
- **Req**: `UpdateProfileRequest` (nickname, email, phone, avatarUrl, bio, locale, timezone, settings)
- **Resp**: `UserInfo`

#### 7.1.11 Get by System User ID
- **Path**: `GET /api/users/get/{userId}`
- **Desc**: Query by system userId (`sys_user`)
- **Auth**: JWT
- **Path Var**: `userId` (UUID system userId)
- **Resp**: `SysUser`
- **Note**: For internal debug/compat; typically use Keycloak ID

#### 7.1.12 Get by Username
- **Path**: `GET /api/users/username/{username}`
- **Desc**: Query by username (`sys_user`)
- **Auth**: JWT
- **Path Var**: `username`
- **Resp**: `SysUser`

#### 7.1.13 Legacy (Deprecated)
- **Path**: `POST /api/users/players/batch`, `GET /api/users/players/{userId}`
- **Desc**: Legacy redirect to new APIs
- **Status**: `@Deprecated`

### 7.2 Friend APIs

#### 7.2.1 Apply Friend
- **Path**: `POST /api/friends/apply`
- **Desc**: Apply; supports mutual auto-accept
- **Auth**: JWT
- **Req**: `ApplyFriendRequest` (targetUserId, requestMessage)
- **Resp**: `ApiResponse<Void>`
- **Logic**:
  - If reverse pending exists, auto accept → “已自动成为好友”
  - Else create request → “申请已发送，等待对方处理”

#### 7.2.2 Accept Friend Request
- **Path**: `POST /api/friends/requests/{id}/accept`
- **Desc**: Accept (receiver)
- **Auth**: JWT
- **Path Var**: requestId
- **Resp**: `ApiResponse<Void>`
- **Logic**: set `ACCEPTED`, create two relations, clear receiver actions, notify requester

#### 7.2.3 Reject Friend Request
- **Path**: `POST /api/friends/requests/{id}/reject`
- **Desc**: Reject (receiver)
- **Auth**: JWT
- **Path Var**: requestId
- **Resp**: `ApiResponse<Void>`
- **Logic**: set `REJECTED`, clear actions, notify requester

#### 7.2.4 Get Friend List
- **Path**: `GET /api/friends`
- **Desc**: Current user friend list
- **Auth**: JWT
- **Resp**: `List<FriendInfo>`
- **Logic**: query ACTIVE relations; batch `UserInfo`; assemble list

#### 7.2.5 Check Friend
- **Path**: `GET /api/friends/check/{userId1}/{userId2}`
- **Desc**: Check friendship (for private chat pre-check)
- **Auth**: JWT
- **Path Var**: Keycloak userId1/userId2
- **Resp**: `Boolean`

### 7.3 Notification APIs

#### 7.3.1 List Notifications
- **Path**: `GET /api/notifications`
- **Desc**: List notifications (status filter, unread-first)
- **Auth**: JWT
- **Query**:
  - `status` optional (UNREAD/READ)
  - `limit` default 10, max 100
- **Resp**: `List<NotificationView>`
- **Sort**: unread-first, time desc

#### 7.3.2 Unread Count
- **Path**: `GET /api/notifications/unread-count`
- **Desc**: Count unread
- **Auth**: JWT
- **Resp**: `Long`

#### 7.3.3 Mark Single Read
- **Path**: `POST /api/notifications/{id}/read`
- **Desc**: Mark read (idempotent)
- **Auth**: JWT
- **Path Var**: notificationId
- **Resp**: `Result<Void>`
- **Auth**: userId must match

#### 7.3.4 Mark All Read
- **Path**: `POST /api/notifications/read-all`
- **Desc**: Mark all unread as read (max 500)
- **Auth**: JWT
- **Resp**: `Result<Void>`

#### 7.3.5 Notification Metadata
- **Path**: `GET /api/notifications/metadata`
- **Desc**: Supported notification types metadata
- **Auth**: None
- **Resp**: `List<NotificationTypeMetadata>`
- **Types**:
  - `FRIEND_REQUEST`: actionable (ACCEPT/REJECT)
  - `FRIEND_RESULT`: non-actionable
  - `SYSTEM_ALERT`: non-actionable

### 7.4 File APIs

#### 7.4.1 Upload Avatar to Temp
- **Path**: `POST /api/files/upload/avatar/temp`
- **Desc**: Upload avatar to temp (for profile update)
- **Auth**: JWT
- **Req**: `multipart/form-data` (file)
- **Resp**: `Result<String>` (temp URL)
- **Validate**: JPG/JPEG/PNG/GIF/WEBP, max 2MB
- **Name**: `{userId}.{ext}`

#### 7.4.2 Upload Avatar (Old, Deprecated)
- **Path**: `POST /api/files/upload/avatar`
- **Desc**: Old method, UUID filename to final dir (compat)
- **Auth**: None (temporarily open for test)
- **Status**: `@Deprecated`

#### 7.4.3 Upload Game Replay
- **Path**: `POST /api/files/upload/replay`
- **Desc**: Upload replay
- **Auth**: None (temporarily open)
- **Req**: `multipart/form-data` (file, gameType, roomId)
- **Resp**: `Result<String>` (URL)
- **Path**: `{gameType}/{roomId}/replay.json`

#### 7.4.4 Upload Material
- **Path**: `POST /api/files/upload/material`
- **Desc**: Upload activity material
- **Auth**: None (temporarily open)
- **Req**: `multipart/form-data` (file, type, id)
- **Resp**: `Result<String>` (URL)
- **Path**: `{type}/{id}/{originalFilename}`

### 7.5 Auth APIs

#### 7.5.1 Get Token
- **Path**: `POST /api/auth/token`
- **Desc**: Get JWT token (password grant, dev/test only)
- **Auth**: None
- **Req**: `LoginRequest` (username, password)
- **Resp**: `TokenResponse` (accessToken, refreshToken, expiresIn ...)
- **Note**: Production should use auth code flow

### 7.6 Internal APIs

#### 7.6.1 Keycloak Event Callback
- **Path**: `POST /internal/keycloak/events`, `POST /internal/keycloak/events/**`
- **Desc**: Receive Keycloak webhook (from vymalo/keycloak-webhook)
- **Auth**: Basic Auth (`event-webhook-basic-username` + `event-webhook-basic-password`)
- **Req**: JSON payload
- **Resp**: `Result<Void>`
- **Process**: handle `REGISTER`, auto-create local user

#### 7.6.2 Session Monitor
- **Path**: `GET /internal/sessions`
- **Desc**: All online sessions (with nickname), dev/debug
- **Auth**: None (open)
- **CORS**: `@CrossOrigin(origins = "*")`
- **Resp**: `List<UserSessionSnapshotWithUserInfo>`
- **Logic**:
  - Get sessions from `SessionRegistry`
  - Batch query user info fill nickname/username
  - Return session snapshots with user info

---

## VIII. Frontend Interaction

### 8.1 User Management

#### 8.1.1 User Sync

**Frontend Call**: `POST /api/users/sync`

**Scenario**: First login sync to system DB

**Flow**:
1. Frontend gets user info from JWT
2. Call `/api/users/sync`
3. If 404, prompt user not found

#### 8.1.2 Profile Update

**Frontend Call**: `PUT /api/users/me/profile`

**Scenario**: User updates profile page

**Flow**:
1. Upload avatar to temp (`POST /api/files/upload/avatar/temp`)
2. Call `PUT /api/users/me/profile` (avatar URL auto moved to final)
3. Refresh user cache

### 8.2 Friend System

#### 8.2.1 Friend Request Flow

**Frontend Calls**:
1. `POST /api/friends/apply` to apply
2. WebSocket receive `/user/queue/notify` (`FRIEND_REQUEST`)
3. `POST /api/friends/requests/{id}/accept` or `/reject`
4. WebSocket receive `/user/queue/notify` (`FRIEND_RESULT`)

**Frontend Events**:
- After accept, trigger `gh-friend-list-refresh` to auto refresh list

**Flow**:
1. User A applies to B
2. Backend creates request, stores notification, pushes via chat-service
3. User B gets WS notify, shows ACCEPT/REJECT
4. B clicks accept, calls API
5. Backend creates two relations, clears actions, notifies requester
6. User A receives result via WS
7. Frontend triggers `gh-friend-list-refresh`

#### 8.2.2 Friend List Query

**Frontend Call**: `GET /api/friends`

**Scenario**: Show in chat or friend list page

**Resp**: `List<FriendInfo>` (with `UserInfo`)

### 8.3 Notification System

#### 8.3.1 Notification Query

**Frontend Calls**:
1. `GET /api/notifications?limit=10` (unread-first)
2. `GET /api/notifications/unread-count`

**Scenario**: Notification center (`MessageCenterPage.jsx`) or bell (`Header.jsx`)

**Sort**: frontend re-sorts (unread-first, time desc) to avoid refresh after actions

#### 8.3.2 Notification Actions

**Frontend Calls**:
1. `POST /api/notifications/{id}/read` mark read
2. `POST /api/notifications/read-all` mark all read
3. `POST /api/friends/requests/{id}/accept` or `/reject` handle friend request

**Scenario**:
- Click notification: default mark read
- Friend request (`type=FRIEND_REQUEST`, `actions` includes ACCEPT/REJECT): render buttons, call accept/reject, on success mark read/remove actions, unread -1

#### 8.3.3 WebSocket Notify Push

**Frontend Subscribe**: `/user/queue/notify` (via `chatSocket.js`)

**Message**:
```json
{
  "type": "FRIEND_REQUEST",
  "title": "好友申请",
  "content": "xxx 请求加你为好友",
  "fromUserId": "...",
  "payload": {
    "friendRequestId": "...",
    "requestMessage": "...",
    "requesterName": "...",
    "notificationId": "..."
  },
  "actions": ["ACCEPT", "REJECT"]
}
```

**Frontend Handling**:
1. Receive, append list (dedup)
2. Unread-first sort, keep top 10
3. Unread count +1 for new unread
4. Fire `gh-notify` custom event

**Flow**:
1. Backend Feign push via chat-service
2. chat-service WS push to frontend
3. `chatSocket.js` receives, calls `notifyListeners`
4. `notifyListeners` dispatch → `gh-notify`
5. `Header.jsx` / `MessageCenterPage.jsx` listen and update list/count

### 8.4 File Upload

#### 8.4.1 Avatar Upload Flow

**Frontend Calls**:
1. `POST /api/files/upload/avatar/temp` to temp
2. `PUT /api/users/me/profile` to update (auto move avatar)

**Scenario**: User uploads avatar in profile page

**Flow**:
1. Select image
2. Call upload temp, get temp URL
3. Preview temp URL
4. Confirm → call update with temp URL
5. Backend moves to final, normalize `{userId}.{ext}`
6. Return final URL

---

## IX. Scalability Analysis

### 9.1 Notification System Scalability

#### 9.1.1 Strong Parts ✅

**Data model is generic**:
- `Notification` fields:
  - `type`: free string
  - `actions`: `List<String>` arbitrary
  - `payload`: `Map<String,Object>` arbitrary
  - `refType` + `refId`: business linking/dedup/status lookup
- Advantage: add type without DB change

**Service methods are generic**:
- `listNotifications()`, `markRead()`, `markAllRead()`, `countUnread()`, `clearNotificationActions()`
- Advantage: apply to all types

**Metadata mechanism**:
- `NotificationTypeMetadata`
- `/api/notifications/metadata`
- Advantage: frontend can know type properties

#### 9.1.2 Weak Parts ❌

**Backend hardcoding**:
- Type-specific methods:
  ```java
  void notifyFriendRequest(...);
  void notifyFriendResult(...);
  ```
- Status query hardcoded:
  ```java
  if ("FRIEND_REQUEST".equals(n.getType()) 
      && (n.getActions() == null || n.getActions().isEmpty()) 
      && n.getRefId() != null) {
      friendRequestRepository.findById(n.getRefId())...;
  }
  ```
- Impact: adding actionable type needs new method, impl, toView logic, repo injection.

**Frontend hardcoding**:
- Action handlers hardcoded; render logic hardcoded; metadata unused; buttons/text hardcoded. Adding new type needs code changes and new handlers.

#### 9.1.3 Cost Evaluation

**Scenario 1: simple notify (no action)**  
- Example: maintenance notice  
- Work: backend use generic create; frontend auto renders; metadata register.  
- Cost: Low ⭐

**Scenario 2: actionable notify**  
- Example: GAME_INVITE (ACCEPT/REJECT)  
- Work: backend add method, status query, metadata, repo; frontend add handler, render, texts, API.  
- Cost: Medium ⭐⭐⭐

**Scenario 3: complex actionable**  
- Example: TEAM_INVITE (ACCEPT/REJECT/VIEW_DETAIL)  
- Work: similar to Scenario 2 but more APIs/state.  
- Cost: Medium-High ⭐⭐⭐⭐

#### 9.1.4 Improvement Suggestions

**Short term (1-2 sprints)**:
1. Frontend use metadata for dynamic render
2. Unified action handler map
3. Button labels configurable (metadata/payload)

**Mid term (3-6 sprints)**:
1. Backend status resolver strategy (`NotificationStatusResolver`)
2. Generic `notify()` method
3. Unified action API `/api/notifications/{id}/actions/{action}`

**Long term (6+ months)**:
1. Pluginized handler architecture
2. Docs/examples for adding types

**Expected**:
- Short: simple type cost -50%
- Mid: actionable type cost -70%
- Long: type cost -90%, fully decoupled

#### 9.1.5 Detailed Plan

> Note: Below are improvement proposals, not current implementation (current in §4.3).

**Plan A: Progressive (recommended)**  
Backend: generic notify method; status resolver strategy.  
Frontend: metadata-driven render; action handler map; configurable labels.

**Plan B: Full refactor (long term)**  
Pluginized NotificationHandler per type; unified action API; frontend posts actions to `/api/notifications/{id}/actions/{action}`; backend resolves via handler.

Known Risks & Suggestions:
1. API prefix hardcoded → make configurable.  
2. No pagination/history → add pagination/load more.  
3. Unread count inconsistency → refresh unread-count after actions.  
4. WS dedup relies on id → ensure `notificationId` always present.  
5. Security → internal push API only via gateway; add IP whitelist/signature in prod.

### 9.2 Friend System Scalability

Current: mutual auto-accept; remark/group/favorite; relationship check.  
Extensibility: data model flexible; logic clear; missing APIs for group/remark update.  
Suggestions: add group management APIs; remark update; support BLOCKED state.

### 9.3 User Management Scalability

Current: sync/create/update/delete; profile mgmt; cache.  
Extensibility: layered model (`sys_user` + `sys_user_profile`); cache 2h; Keycloak two-way sync.  
Gaps: no user search API; no paginated list API.  
Suggestions: add search (username/nickname fuzzy); add list pagination with sort/filter.

---

## X. Summary

### 10.1 Core Functions

system-service, as core system-domain service, provides complete user management, friend system, notification system, file storage, and deep Keycloak integration for two-way user sync.

### 10.2 Technical Highlights

1. **User cache**: Redis, TTL 2h, proactive refresh/evict, performance boost  
2. **Mutual auto-accept**: better UX  
3. **Notifications persist then push**: offline visible  
4. **Keycloak event listener**: auto local user creation  
5. **Avatar normalization**: unified `{userId}.{ext}` naming

### 10.3 Scalability

- **Notifications**: generic data model, but logic hardcoded; suggest gradual pluginization
- **Friends**: flexible model, clear logic, missing some mgmt APIs
- **User mgmt**: clear layering, good cache, missing search/list APIs

### 10.4 To-Be-Completed

1. RBAC business logic  
2. User search (username/nickname fuzzy)  
3. User list pagination/sort/filter  
4. Friend group management APIs  
5. Friend remark update API

---

**Document Maintenance**: Update this doc with new features or changes to keep 100% aligned with code.

