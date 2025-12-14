# System-Service 技术文档

> 本文档详细说明 system-service 的技术实现，包括代码架构、功能实现、配置说明、数据模型、API清单、前端交互等。

---

## 目录

1. [服务概述](#一服务概述)
2. [代码架构](#二代码架构)
3. [配置说明](#三配置说明)
4. [核心功能实现](#四核心功能实现)
5. [数据模型](#五数据模型)
6. [OpenFeign 配置与使用](#六openfeign-配置与使用)
7. [API 清单](#七api-清单)
8. [前端交互](#八前端交互)
9. [扩展性分析](#九扩展性分析)

---

## 一、服务概述

### 1.1 服务定位

**system-service** 是平台中的**系统域核心服务**，负责所有与"用户管理"和"系统功能"相关的功能，包括：

- **用户管理**：用户同步、创建、更新、删除、资料管理
- **好友系统**：好友申请、同意/拒绝、好友列表、好友关系检查
- **通知系统**：全局通知创建、查询、未读计数、已读标记、元数据管理
- **文件存储**：头像、游戏回放、活动素材的上传和管理
- **Keycloak 集成**：用户注册事件监听、自动创建本地用户
- **会话监控**：在线用户会话查询（开发调试用）
- **RBAC 权限系统**：角色、权限实体定义（业务逻辑待完善）

### 1.2 技术栈

- **框架**：Spring Boot 3.x + Spring Data JPA
- **安全**：Spring Security + OAuth2 Resource Server (Keycloak JWT)
- **数据存储**：PostgreSQL (JPA/Hibernate) + Redis
- **服务调用**：Spring Cloud OpenFeign + Resilience4j 熔断
- **文件存储**：MinIO
- **身份认证**：Keycloak Admin Client
- **服务发现**：Spring Cloud LoadBalancer
- **会话管理**：session-common（提供 `SessionRegistry` 和 `sessionRedisTemplate`）

### 1.3 核心职责

| 功能模块 | 职责 |
|---------|------|
| 用户管理 | Keycloak 与系统数据库的双向同步，用户资料管理，用户信息缓存 |
| 好友系统 | 好友申请流程，双向申请自动通过，好友关系维护 |
| 通知系统 | 通知创建与落库，通过 Feign 调用 chat-service 推送，通知查询与状态管理 |
| 文件存储 | MinIO 文件上传，头像规范化，临时文件管理 |
| Keycloak 事件 | 监听注册事件，自动创建本地用户，幂等性保护 |
| 会话监控 | 查询在线用户会话，显示用户信息（开发调试用） |

---

## 二、代码架构

### 2.1 包结构

```
com.gamehub.systemservice
├── SystemServiceApplication          # 启动类
├── common/                           # 通用类
│   └── Result.java                   # 统一响应结果
├── config/                           # 配置类
│   ├── SecurityConfig                # Spring Security + OAuth2
│   ├── KeycloakConfig                # Keycloak Admin Client 配置
│   ├── MinioConfig                   # MinIO 客户端配置
│   └── RestTemplateConfig            # RestTemplate 配置
├── controller/                       # 控制器层
│   ├── AuthController                # 认证接口（Token 获取）
│   ├── friend/                       # 好友相关接口
│   │   └── FriendController
│   ├── notification/                 # 通知相关接口
│   │   └── NotificationController
│   ├── user/                         # 用户相关接口
│   │   ├── UserController
│   │   └── UserProfileController
│   ├── file/                         # 文件相关接口
│   │   └── FileController
│   ├── internal/                     # 内部接口
│   │   └── KeycloakEventController   # Keycloak 事件回调
│   └── SessionMonitorController      # 会话监控接口
├── service/                          # 服务层
│   ├── user/                         # 用户服务
│   │   ├── UserService
│   │   ├── UserProfileCacheService   # 用户信息缓存服务
│   │   └── impl/
│   │       └── UserServiceImpl
│   ├── friend/                       # 好友服务
│   │   ├── FriendService
│   │   └── impl/
│   │       └── FriendServiceImpl
│   ├── notification/                 # 通知服务
│   │   ├── NotificationService
│   │   ├── dto/
│   │   │   ├── NotificationView
│   │   │   └── NotificationTypeMetadata
│   │   └── impl/
│   │       └── NotificationServiceImpl
│   ├── file/                         # 文件存储服务
│   │   ├── FileStorageService
│   │   └── impl/
│   │       └── MinioFileStorageService
│   └── keycloak/                     # Keycloak 事件服务
│       ├── KeycloakEventService
│       └── impl/
│           └── KeycloakEventServiceImpl
├── entity/                           # 实体类
│   ├── user/                         # 用户实体
│   │   ├── SysUser                   # 用户表
│   │   └── SysUserProfile           # 用户扩展表
│   ├── friend/                       # 好友实体
│   │   ├── FriendRequest             # 好友申请表
│   │   └── UserFriend                # 好友关系表
│   ├── notification/                 # 通知实体
│   │   └── Notification              # 通知表
│   ├── role/                         # 角色实体（RBAC）
│   │   ├── SysRole
│   │   ├── SysRolePermission
│   │   ├── SysUserRole
│   │   └── ...
│   └── permission/                   # 权限实体（RBAC）
│       └── SysPermission
├── repository/                       # 仓储接口
│   ├── user/
│   │   ├── SysUserRepository
│   │   └── SysUserProfileRepository
│   ├── friend/
│   │   ├── FriendRequestRepository
│   │   └── UserFriendRepository
│   ├── notification/
│   │   └── NotificationRepository
│   └── role/、permission/           # RBAC 相关 Repository
├── dto/                              # DTO 类
│   ├── request/                      # 请求 DTO
│   │   ├── ApplyFriendRequest
│   │   ├── CreateUserRequest
│   │   ├── LoginRequest
│   │   ├── UpdateProfileRequest
│   │   └── UpdateUserRequest
│   ├── response/                     # 响应 DTO
│   │   ├── FriendInfo
│   │   ├── TokenResponse
│   │   ├── UserInfo
│   │   └── UserSessionSnapshotWithUserInfo
│   └── keycloak/
│       └── KeycloakEventPayload
├── infrastructure/                   # 基础设施层
│   └── client/                       # Feign 客户端
│       └── chat/
│           ├── ChatNotifyClient      # 调用 chat-service 推送通知
│           ├── ChatNotifyClientFallback
│           └── dto/
│               └── NotifyPushRequest
├── exception/                        # 异常处理
│   ├── BusinessException
│   └── GlobalExceptionHandler
└── SystemServiceApplication          # 启动类
```

### 2.2 关键类职责

| 类名 | 职责 |
|------|------|
| `UserServiceImpl` | 用户同步、创建、更新、删除、资料管理、批量查询，Keycloak 双向同步 |
| `FriendServiceImpl` | 好友申请、同意/拒绝、双向申请自动通过、好友列表查询、好友关系检查 |
| `NotificationServiceImpl` | 通知创建、查询、未读计数、已读标记、清除操作按钮，调用 Feign 推送 |
| `MinioFileStorageService` | 文件上传、头像规范化、临时文件管理 |
| `KeycloakEventServiceImpl` | 处理 Keycloak 注册事件，自动创建本地用户 |
| `UserProfileCacheService` | 用户信息缓存（Redis，TTL 2小时） |
| `ChatNotifyClient` | Feign 客户端，调用 chat-service 推送通知 |
| `GlobalExceptionHandler` | 全局异常处理，统一错误响应格式 |

---

## 三、配置说明

### 3.1 Spring Security 配置

**文件位置**：`config/SecurityConfig.java`

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

**说明**：
- 作为 OAuth2 Resource Server，验证从 Gateway 传来的 JWT Token
- 无状态会话（使用 JWT，不需要 Session）
- 部分接口放行（注册、Token 获取、Keycloak 事件回调、会话监控、文件上传）

### 3.2 Keycloak Admin Client 配置

**文件位置**：`config/KeycloakConfig.java`

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

**配置项**（`application.yml`）：
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

**说明**：
- 使用用户名密码方式认证（适用于开发环境）
- 用于通过 Admin API 创建和管理 Keycloak 用户
- Keycloak 事件回调使用 Basic Auth 鉴权

### 3.3 MinIO 配置

**文件位置**：`config/MinioConfig.java`

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

**配置项**（`application.yml`）：
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

**说明**：
- 支持多个 Bucket：avatars（头像）、game-replays（游戏回放）、materials（活动素材）、temp（临时文件）
- 临时文件过期时间：24小时
- 公共访问 URL 前缀：直连 MinIO，无 Nginx

### 3.4 数据源配置

**配置项**（`application.yml`）：
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

### 3.5 Redis 配置

**配置项**（`application.yml`）：
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

# session 会话管理配置（用于会话监控功能）
session:
  redis:
    host: ${SESSION_REDIS_HOST:127.0.0.1}
    port: ${SESSION_REDIS_PORT:6379}
    database: ${SESSION_REDIS_DATABASE:0}
    password: ${SESSION_REDIS_PASSWORD:zaqxsw}
```

**说明**：
- **Spring Data Redis**：使用 database 3（当前未使用，预留）
- **Session Redis**：使用 database 0（会话监控和用户信息缓存）
  - `UserProfileCacheService` 使用 `session-common` 库提供的 `sessionRedisTemplate`（通过 `@Qualifier("sessionRedisTemplate")` 注入）
  - `SessionMonitorController` 使用 `SessionRegistry`（也依赖 `sessionRedisTemplate`）
  - `sessionRedisTemplate` 由 `session-common` 库的 `SessionRedisConfig` 自动配置，通过 `session.redis.*` 配置项配置连接信息

### 3.6 OpenFeign 配置

**配置项**（`application.yml`）：
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

**说明**：
- 本地开发无注册中心，使用 Simple Discovery 配置 chat-service 实例
- 避免 LoadBalancer 503 错误

---

## 四、核心功能实现

### 4.1 用户管理

#### 4.1.1 用户同步（Keycloak ↔ System-Service）

**实现位置**：`service/user/impl/UserServiceImpl.syncUser()`

**流程**：
1. 根据 Keycloak 用户ID查询系统用户
2. 如果存在，更新用户名和邮箱
3. 如果不存在，创建新用户和用户扩展信息

**代码示例**：
```java
@Transactional
public SysUser syncUser(UUID keycloakUserId, String username, String email) {
    Optional<SysUser> existingUser = userRepository.findByKeycloakUserIdAndNotDeleted(keycloakUserId);
    
    if (existingUser.isPresent()) {
        // 更新现有用户
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
        // 创建新用户
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
        
        // 创建用户扩展信息
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

**关键点**：
- 使用数据库序列生成全局唯一的 `playerId`
- 自动创建用户扩展信息（`SysUserProfile`）
- 用户名唯一性校验

#### 4.1.2 用户创建（Keycloak + System-Service）

**实现位置**：`service/user/impl/UserServiceImpl.createUser()`

**流程**：
1. 检查系统数据库中用户名是否已存在
2. 检查 Keycloak 中用户名是否已存在
3. 在 Keycloak 中创建用户
4. 设置 Keycloak 用户密码
5. 在系统数据库中创建用户和用户扩展信息
6. 如果任何步骤失败，回滚已创建的资源

**关键点**：
- 事务性保证：Keycloak 和系统数据库的一致性
- 失败回滚：如果系统数据库创建失败，删除 Keycloak 用户
- 密码设置失败时，删除已创建的 Keycloak 用户

#### 4.1.3 用户更新（Keycloak + System-Service 双向同步）

**实现位置**：`service/user/impl/UserServiceImpl.updateUser()`

**同步字段**：
- **昵称**：同步更新 Keycloak 的 `firstName` 和系统数据库的 `nickname`
- **邮箱**：同步更新 Keycloak 的 `email` 和系统数据库的 `email`
- **密码**：只更新 Keycloak（系统数据库不存储密码）

**代码示例**：
```java
@Transactional
public SysUser updateUser(UUID userId, String nickname, String password, String email) {
    SysUser user = userRepository.findById(userId)
            .filter(u -> u.getDeletedAt() == null)
            .orElseThrow(() -> new BusinessException("用户不存在或已被删除"));
    
    // 更新昵称（同步 Keycloak）
    if (nickname != null && !nickname.isBlank()) {
        user.setNickname(nickname);
        RealmResource realmResource = keycloak.realm(realm);
        UserRepresentation keycloakUser = realmResource.users()
                .get(user.getKeycloakUserId().toString())
                .toRepresentation();
        keycloakUser.setFirstName(nickname);
        realmResource.users().get(user.getKeycloakUserId().toString()).update(keycloakUser);
    }
    
    // 更新邮箱（同步 Keycloak）
    if (email != null && !email.isBlank()) {
        Optional<SysUser> existingUser = userRepository.findByEmailAndNotDeleted(email);
        if (existingUser.isPresent() && !existingUser.get().getId().equals(userId)) {
            throw new BusinessException("邮箱已被其他用户使用: " + email);
        }
        user.setEmail(email);
        // 同步更新 Keycloak
        // ...
    }
    
    // 更新密码（只更新 Keycloak）
    if (password != null && !password.isBlank()) {
        // 只更新 Keycloak
        // ...
    }
    
    return userRepository.save(user);
}
```

#### 4.1.4 用户删除（软删除）

**实现位置**：`service/user/impl/UserServiceImpl.deleteUser()`

**流程**：
1. 在 Keycloak 中禁用用户（不删除，保留数据）
2. 在系统数据库中软删除并禁用（设置 `deletedAt` 和 `status=0`）
3. 清理用户信息缓存

**关键点**：
- 软删除：保留数据，便于数据恢复和审计
- Keycloak 禁用失败不影响系统数据库软删除（容错）

#### 4.1.5 用户信息查询（批量、缓存）

**实现位置**：`service/user/impl/UserServiceImpl.findUserInfosByKeycloakUserIds()`

**流程**：
1. 先尝试从 Redis 缓存获取（`UserProfileCacheService`）
2. 缓存未命中时，批量查询数据库（`sys_user` + `sys_user_profile`）
3. 组装 `UserInfo` DTO（聚合用户基础信息和扩展信息）
4. 写回缓存（TTL 2小时）
5. 按请求顺序返回结果

**缓存策略**：
- **Key 格式**：`user:profile:{keycloakUserId}`
- **TTL**：2小时
- **Redis 配置**：
  - 使用 `session-common` 库提供的 `sessionRedisTemplate`（通过 `@Qualifier("sessionRedisTemplate")` 注入）
  - 使用 Session Redis（database 0），跨服务共享
  - `sessionRedisTemplate` 由 `session-common` 库的 `SessionRedisConfig` 自动配置（Bean 名称：`sessionRedisTemplate`）
- **序列化方式**：值使用 JSON 手动序列化（`ObjectMapper`），避免 Bean 冲突
- **更新时机**：用户资料更新时主动刷新缓存（`UserProfileCacheService.put()`）
- **失效时机**：用户删除时主动清除缓存（`UserProfileCacheService.evict()`）

**代码示例**：
```java
public List<UserInfo> findUserInfosByKeycloakUserIds(List<String> keycloakUserIds) {
    // 先尝试缓存命中
    Map<String, UserInfo> hitCache = new LinkedHashMap<>();
    List<String> misses = new ArrayList<>();
    for (String id : keycloakUserIds) {
        userProfileCacheService.get(id).ifPresentOrElse(
                info -> hitCache.put(id, info),
                () -> misses.add(id)
        );
    }
    
    // 缓存未命中，查询数据库
    Map<String, UserInfo> dbResultMap = new LinkedHashMap<>();
    if (!misses.isEmpty()) {
        List<SysUser> users = userRepository.findByKeycloakUserIdInAndNotDeleted(uuidList);
        // 查询用户扩展信息
        // 组装 UserInfo
        // 写回缓存
        userProfileCacheService.put(info);
    }
    
    // 按请求顺序返回
    // ...
}
```

#### 4.1.6 用户资料更新

**实现位置**：`service/user/impl/UserServiceImpl.updateProfile()`

**流程**：
1. 处理头像（临时URL → 正式目录，规范化文件名）
2. 更新 `sys_user` 表（昵称、邮箱、手机号、头像URL，同步 Keycloak）
3. 更新或创建 `sys_user_profile` 表（bio、locale、timezone、settings）
4. 刷新缓存

**头像处理逻辑**：
- **临时URL**（`/temp/`）：移动到 `avatars` 目录，重命名为 `{userId}.{ext}`
- **已在 avatars**：规范化文件名为 `{userId}.{ext}`
- **其他路径**：直接使用

**代码示例**：
```java
@Transactional
public UserInfo updateProfile(String keycloakUserId, String nickname, String email, String phone,
                              String avatarUrl, String bio, String locale, String timezone,
                              Map<String, Object> settings) {
    // 1. 查找用户
    UUID keycloakUserIdUuid = UUID.fromString(keycloakUserId);
    SysUser user = userRepository.findByKeycloakUserIdAndNotDeleted(keycloakUserIdUuid)
            .orElseThrow(() -> new BusinessException("用户不存在"));
    
    // 2. 处理头像
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
    
    // 3. 更新 sys_user（同步 Keycloak）
    // ...
    
    // 4. 更新或创建 sys_user_profile
    // ...
    
    // 5. 刷新缓存
    userProfileCacheService.put(updated);
    return updated;
}
```

### 4.2 好友系统

#### 4.2.1 好友申请

**实现位置**：`service/friend/impl/FriendServiceImpl.applyFriend()`

**流程**：
1. 参数验证：不能添加自己为好友
2. 留言规范化：去除首尾空格，空字符串视为 null，裁剪至 200 字符
3. 将 Keycloak 用户ID转换为 UUID
4. 查询申请人和目标用户的系统用户ID
5. 检查是否已经是好友
6. 检查是否已有待处理的申请
7. **检查是否存在反向的待处理申请（双向申请自动通过）**
8. 如果不存在反向申请，创建新的申请记录并推送通知

**双向申请自动通过逻辑**：
```java
Optional<FriendRequest> reverseRequest = friendRequestRepository.findReversePendingRequest(requesterId, targetId);

if (reverseRequest.isPresent()) {
    // 双向申请自动通过
    return handleMutualRequest(requesterId, targetId, reverseRequest.get(), normalizedMessage);
} else {
    // 正常申请流程
    FriendRequest created = createFriendRequest(requesterId, targetId, normalizedMessage);
    notificationService.notifyFriendRequest(...);
    return false;
}
```

**`handleMutualRequest()` 流程**：
1. 更新反向申请状态为 `ACCEPTED`
2. 创建正向申请记录并设置为 `ACCEPTED`
3. 创建两条好友关系记录（双向）
4. 返回 `true`（表示自动成为好友）

**关键点**：
- 留言长度限制：200 字符
- 双向申请自动通过：提升用户体验
- 事务性保证：申请记录和好友关系的创建在同一事务中

#### 4.2.2 同意/拒绝好友申请

**实现位置**：`service/friend/impl/FriendServiceImpl.handleFriendRequest()`

**流程**：
1. 校验：当前登录用户必须是接收方，状态必须 `PENDING`
2. 更新申请状态：`ACCEPTED` 或 `REJECTED`
3. 如果同意，创建双向好友关系（`UserFriend`）
4. 清除接收方通知的操作按钮（避免重新登录后仍显示操作按钮）
5. 通知申请人结果（`FRIEND_RESULT`）

**代码示例**：
```java
private void handleFriendRequest(String receiverKeycloakUserId, UUID requestId, boolean accept) {
    FriendRequest request = friendRequestRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException(404, "好友申请不存在"));
    
    // 仅接收方可处理，且必须是待处理状态
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
    
    // 同意则建立好友关系（双向）
    if (accept) {
        createFriendRelation(request.getRequesterId(), request.getReceiverId(), now);
    }
    
    // 清除接收方通知的操作按钮
    notificationService.clearNotificationActions(receiverSystemUserId, "FRIEND_REQUEST", requestId);
    
    // 通知申请人结果
    notifyRequesterResult(request, accept);
}
```

**关键点**：
- 权限校验：仅接收方可处理
- 状态校验：必须是 `PENDING` 状态
- 双向好友关系：创建两条 `UserFriend` 记录
- 通知操作按钮清除：处理完成后不再显示操作按钮

#### 4.2.3 好友列表查询

**实现位置**：`service/friend/impl/FriendServiceImpl.getFriendsList()`

**流程**：
1. 将 Keycloak 用户ID转换为 UUID
2. 查询系统用户ID
3. 查询当前用户的所有好友关系（状态为 `ACTIVE`）
4. 批量查询好友的系统用户信息
5. 批量查询好友的完整用户信息（`UserInfo`）
6. 组装 `FriendInfo` 列表

**代码示例**：
```java
public List<FriendInfo> getFriendsList(String currentUserKeycloakUserId) {
    UUID currentUserKeycloakUuid = UUID.fromString(currentUserKeycloakUserId);
    UUID currentSystemUserId = getSystemUserId(currentUserKeycloakUuid);
    
    // 查询当前用户的所有好友关系（状态为ACTIVE）
    List<UserFriend> friendRelations = userFriendRepository.findActiveFriendsByUserId(currentSystemUserId);
    
    // 提取所有好友的系统用户ID
    List<UUID> friendSystemUserIds = friendRelations.stream()
            .map(UserFriend::getFriendId)
            .distinct()
            .collect(Collectors.toList());
    
    // 批量查询好友的系统用户信息
    List<SysUser> friendSysUsers = sysUserRepository.findAllById(friendSystemUserIds);
    
    // 提取所有好友的 Keycloak 用户ID
    List<String> friendKeycloakUserIds = friendSysUsers.stream()
            .map(user -> user.getKeycloakUserId().toString())
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    
    // 批量查询好友的完整用户信息（UserInfo）
    List<UserInfo> friendUserInfos = userService.findUserInfosByKeycloakUserIds(friendKeycloakUserIds);
    
    // 组装 FriendInfo 列表
    // ...
}
```

**关键点**：
- 批量查询：减少数据库查询次数
- 使用 `UserInfo`：复用用户信息查询逻辑（包含缓存）

#### 4.2.4 好友关系检查

**实现位置**：`service/friend/impl/FriendServiceImpl.isFriend()`

**用途**：用于私聊等功能的前置验证

**流程**：
1. 参数校验
2. 将 Keycloak 用户ID转换为 UUID
3. 查询两个用户的系统用户ID
4. 检查是否是好友关系（状态为 `ACTIVE`）

### 4.3 通知系统

#### 4.3.1 通知创建（好友申请）

**实现位置**：`service/notification/impl/NotificationServiceImpl.notifyFriendRequest()`

**流程**：
1. 落库：创建 `Notification` 记录（`sys_notification` 表）
2. 推送：调用 `ChatNotifyClient.push()` 通过 WebSocket 实时推送
3. 推送失败不影响主事务（仅记录 warn 日志）

**代码示例**：
```java
@Transactional(rollbackFor = Exception.class)
public void notifyFriendRequest(UUID receiverUserId,
                                String receiverKeycloakUserId,
                                String requesterKeycloakUserId,
                                String requesterName,
                                UUID friendRequestId,
                                String requestMessage) {
    // 1) 落库，确保离线/未读可见
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
    
    // 2) 推送 WS（失败不影响事务）
    try {
        pushToChatService(receiverKeycloakUserId, requesterKeycloakUserId, requesterName, 
                         friendRequestId, requestMessage, notification.getId());
    } catch (Exception e) {
        log.warn("推送好友申请通知失败（已落库，用户可离线查看）：receiver={}, refId={}, err={}",
                receiverKeycloakUserId, friendRequestId, e.getMessage());
    }
}
```

**关键点**：
- **先落库后推送**：确保离线用户也能看到通知
- **推送失败不影响主事务**：通知已落库，用户可离线查看
- **携带 `notificationId`**：便于前端去重和标记已读

#### 4.3.2 通知创建（好友申请结果）

**实现位置**：`service/notification/impl/NotificationServiceImpl.notifyFriendResult()`

**流程**：
1. 落库：创建 `Notification` 记录（类型为 `FRIEND_RESULT`）
2. 推送：调用 `ChatNotifyClient.push()` 通过 WebSocket 实时推送
3. 推送失败不影响主事务

**关键点**：
- 通知申请人处理结果（同意/拒绝）
- `actions` 为空（无需操作）

#### 4.3.3 通知查询

**实现位置**：`service/notification/impl/NotificationServiceImpl.listNotifications()`

**排序规则**：
- **未读优先**：`CASE WHEN n.status = 'UNREAD' THEN 0 ELSE 1 END`
- **时间倒序**：`n.createdAt DESC`

**代码示例**：
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

**关键点**：
- 支持状态过滤（`status` 参数）
- 未读优先排序：提升用户体验
- 分页支持：`limit` 参数（最大 100）

#### 4.3.4 未读计数

**实现位置**：`service/notification/impl/NotificationServiceImpl.countUnread()`

**实现**：
```java
@Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.status = 'UNREAD'")
long countUnread(@Param("userId") UUID userId);
```

#### 4.3.5 标记已读

**实现位置**：`service/notification/impl/NotificationServiceImpl.markRead()`

**流程**：
1. 查询通知
2. 权限校验：`userId` 必须匹配
3. 更新状态为 `READ`，设置 `readAt`

**批量标记已读**：
- 最多处理 500 条，避免一次性更新太多

#### 4.3.6 清除通知操作按钮

**实现位置**：`service/notification/impl/NotificationServiceImpl.clearNotificationActions()`

**用途**：当业务已处理完成时调用，比如好友申请已同意/拒绝后，就不应该再显示操作按钮了

**流程**：
1. 根据 `userId`、`refType`、`refId` 查找通知
2. 清除所有匹配通知的操作按钮（`actions = null`）
3. 如果通知是未读状态，同时标记为已读

**代码示例**：
```java
@Transactional
public void clearNotificationActions(UUID userId, String refType, UUID refId) {
    List<Notification> notifications = notificationRepository.findByUserIdAndRefTypeAndRefId(userId, refType, refId);
    
    notifications.forEach(n -> {
        n.setActions(null); // 清除操作按钮
        if ("UNREAD".equalsIgnoreCase(n.getStatus())) {
            n.setStatus("READ"); // 同时标记为已读
            n.setReadAt(OffsetDateTime.now());
        }
    });
    notificationRepository.saveAll(notifications);
}
```

#### 4.3.7 通知状态查询（已处理的好友申请）

**实现位置**：`service/notification/impl/NotificationServiceImpl.toView()`

**用途**：对于已处理的好友申请（`actions` 为空），查询实际状态并补充到 `payload` 中

**代码示例**：
```java
private NotificationView toView(Notification n) {
    Map<String, Object> payload = n.getPayload() != null ? new HashMap<>(n.getPayload()) : new HashMap<>();
    
    // 如果是好友申请类型且已处理（actions为空），查询处理状态并添加到payload
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

**关键点**：
- 动态查询业务状态：已处理的好友申请显示实际状态
- 异常容错：查询失败不影响通知展示

#### 4.3.8 通知元数据

**实现位置**：`controller/notification/NotificationController.metadata()`

**用途**：返回支持的通知类型元数据，便于前端展示/提示

**代码示例**：
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

**说明**：
- 实际业务动作仍由前端内置处理，不在此返回可执行代码
- 避免后端配置驱动前端行为过重

### 4.4 文件存储

#### 4.4.1 头像上传（临时目录）

**实现位置**：`service/file/impl/MinioFileStorageService.uploadAvatarToTemp()`

**流程**：
1. 验证文件（类型、大小）
2. 生成文件名（`{userId}.{ext}`）
3. 上传到临时目录（`temp` bucket）
4. 返回临时文件访问 URL

**文件验证**：
- **类型**：只允许 JPG、JPEG、PNG、GIF、WEBP
- **大小**：最大 2MB
- **双重验证**：Content-Type 和文件扩展名

#### 4.4.2 头像规范化

**实现位置**：`service/file/impl/MinioFileStorageService.normalizeAvatarInAvatars()`

**用途**：若头像已在 `avatars` 但文件名不是 `userId`，则重命名为 `userId.xxx`

**流程**：
1. 提取对象名称
2. 如果已经是 `userId` 命名则直接返回
3. 复制并覆盖为 `userId.xxx`
4. 删除旧文件（不影响主流程）

#### 4.4.3 头像从临时目录移动到正式目录

**实现位置**：`service/file/impl/MinioFileStorageService.moveAvatarFromTemp()`

**流程**：
1. 从URL中提取临时文件名
2. 检查临时文件是否存在
3. 复制文件从 `temp` 到 `avatars`（覆盖同名文件）
4. 删除临时文件
5. 返回正式文件访问 URL

**关键点**：
- 文件命名：`{userId}.{ext}`
- 删除临时文件：避免存储浪费

#### 4.4.4 游戏回放上传

**实现位置**：`service/file/impl/MinioFileStorageService.uploadGameReplay()`

**文件路径**：`{gameType}/{roomId}/replay.json`

#### 4.4.5 活动素材上传

**实现位置**：`service/file/impl/MinioFileStorageService.uploadMaterial()`

**文件路径**：`{type}/{id}/{originalFilename}`

### 4.5 Keycloak 事件处理

#### 4.5.1 Keycloak 事件回调

**实现位置**：`controller/internal/KeycloakEventController.onEvent()`

**鉴权**：Basic Auth（`event-webhook-basic-username` + `event-webhook-basic-password`）

**事件格式**：
- **vymalo/keycloak-webhook 插件格式**：直接格式 `{"type": "REGISTER", "userId": "...", "details": {...}}`
- **兼容其他插件**：包装格式 `{"event": {...}}`

**代码示例**：
```java
@PostMapping({"/events", "/events/**"})
public ResponseEntity<Result<Void>> onEvent(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody(required = false) String body) {
    
    // Basic Auth 鉴权
    // ...
    
    // 解析 JSON（优先直接格式，失败则尝试包装格式）
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
            // 尝试包装格式
            payload = mapper.readValue(body, KeycloakEventPayload.class);
        }
    }
    
    // 处理事件（失败也不向上抛 500，避免影响 Keycloak 流程）
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

**关键点**：
- 业务处理失败也不向上抛 500：避免影响 Keycloak 流程
- 兼容多种事件格式：提升兼容性

#### 4.5.2 用户注册事件处理

**实现位置**：`service/keycloak/impl/KeycloakEventServiceImpl.handleEvent()`

**流程**：
1. 只处理 `REGISTER` 事件
2. 检查用户是否已存在（幂等性保护）
3. 优先使用事件 `details` 中的信息（避免额外调用 Keycloak Admin API）
4. 如果 `details` 信息不完整，调用 Keycloak Admin API 获取完整信息
5. 生成玩家ID（数据库序列）
6. 创建本地用户和用户扩展信息

**代码示例**：
```java
@Transactional
public void handleEvent(KeycloakEventPayload payload) {
    if (payload == null || payload.getEvent() == null) {
        return;
    }
    
    KeycloakEventPayload.Event event = payload.getEvent();
    String type = event.getType();
    String userId = event.getUserId();
    
    // 只处理 REGISTER 事件
    if ("REGISTER".equalsIgnoreCase(type)) {
        createLocalUser(event);
    }
}

private void createLocalUser(KeycloakEventPayload.Event event) {
    String keycloakUserId = event.getUserId();
    UUID kcUserUuid = UUID.fromString(keycloakUserId);
    
    // 检查是否已存在（幂等性保护）
    Optional<SysUser> existed = userRepository.findByKeycloakUserIdAndNotDeleted(kcUserUuid);
    if (existed.isPresent()) {
        log.info("用户已存在，跳过创建: keycloakUserId={}", keycloakUserId);
        return;
    }
    
    // 优先使用事件 details 中的信息
    String username = null;
    String email = null;
    String nickname = null;
    
    if (event.getDetails() != null) {
        username = event.getDetails().get("username");
        email = event.getDetails().get("email");
        // 尝试多种可能的字段名（firstName、first_name、firstname）
        String firstName = event.getDetails().get("firstName");
        if (firstName == null || firstName.isBlank()) {
            firstName = event.getDetails().get("first_name");
        }
        if (firstName == null || firstName.isBlank()) {
            firstName = event.getDetails().get("firstname");
        }
        nickname = firstName;
    }
    
    // 如果 details 信息不完整，调用 Keycloak Admin API
    if (needUsername || needEmail || needNickname) {
        // 添加短暂延迟，确保 Keycloak 已保存用户信息
        Thread.sleep(200);
        UserRepresentation kcUser = keycloak.realm(realm).users().get(keycloakUserId).toRepresentation();
        // 获取缺失信息
    }
    
    // 生成玩家ID
    Long playerId = generatePlayerIdFromSequence();
    
    // 创建本地用户
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
    
    // 创建用户扩展信息
    SysUserProfile profile = SysUserProfile.builder()
            .userId(user.getId())
            .locale("zh-CN")
            .timezone("Asia/Shanghai")
            .settings(Map.of())
            .build();
    userProfileRepository.save(profile);
}
```

**关键点**：
- **幂等性保护**：用户已存在则跳过创建
- **优先使用事件 details**：减少 Keycloak Admin API 调用
- **延迟获取**：添加 200ms 延迟，确保 Keycloak 已保存用户信息（特别是 `firstName`）
- **兼容多种字段名**：不同插件可能使用不同的命名方式

### 4.6 会话监控

**实现位置**：`controller/SessionMonitorController.getAllSessions()`

**用途**：用于开发和调试，提供实时查看所有在线用户会话的接口

**流程**：
1. 从 `SessionRegistry` 获取所有用户会话
2. 批量查询用户信息并填充昵称
3. 返回会话快照列表（带用户信息）

**说明**：
- 此接口完全开放，仅用于开发环境排查问题
- 业务边界：此功能属于系统管理功能，放在 system-service 更符合业务边界划分

---

## 五、数据模型

### 5.1 用户相关表

#### 5.1.1 sys_user（用户表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键（系统用户ID） |
| `keycloak_user_id` | UUID | Keycloak 用户ID（对应 JWT 中 sub，唯一） |
| `username` | VARCHAR(50) | 用户名（大小写不敏感，唯一） |
| `nickname` | VARCHAR(50) | 昵称 |
| `email` | VARCHAR(100) | 邮箱（大小写不敏感） |
| `phone` | VARCHAR(20) | 手机号 |
| `avatar_url` | VARCHAR(500) | 头像URL |
| `user_type` | VARCHAR(20) | 用户类型（NORMAL/ADMIN） |
| `dept_id` | UUID | 部门ID |
| `status` | INTEGER | 状态（0-禁用，1-启用） |
| `remark` | VARCHAR(500) | 备注 |
| `player_id` | BIGINT | 玩家ID（唯一数字ID，用于查找和分享） |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |
| `deleted_at` | TIMESTAMP | 软删除时间（NULL 表示未删除） |

**索引**：
- `keycloak_user_id`（唯一索引）
- `username`（唯一索引，软删除过滤）
- `player_id`（唯一索引）

#### 5.1.2 sys_user_profile（用户扩展表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | UUID | 主键（关联 sys_user.id） |
| `bio` | VARCHAR(500) | 个人简介 |
| `locale` | VARCHAR(10) | 语言偏好（如：zh-CN、en-US） |
| `timezone` | VARCHAR(50) | 时区（如：Asia/Shanghai、UTC） |
| `settings` | JSONB | 用户设置 |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |

### 5.2 好友相关表

#### 5.2.1 friend_request（好友申请表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `requester_id` | UUID | 申请人用户ID（系统用户ID） |
| `receiver_id` | UUID | 接收人用户ID（系统用户ID） |
| `request_message` | VARCHAR(200) | 申请留言 |
| `status` | VARCHAR(20) | 申请状态（PENDING/ACCEPTED/REJECTED/EXPIRED） |
| `handled_at` | TIMESTAMP | 处理时间 |
| `created_at` | TIMESTAMP | 创建时间 |

**索引**：
- `(requester_id, receiver_id, status)`（用于查询待处理申请）

#### 5.2.2 user_friend（好友关系表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `user_id` | UUID | 用户ID（系统用户ID） |
| `friend_id` | UUID | 好友用户ID（系统用户ID） |
| `friend_nickname` | VARCHAR(50) | 好友备注昵称 |
| `friend_group` | VARCHAR(50) | 好友分组 |
| `status` | VARCHAR(20) | 关系状态（ACTIVE/BLOCKED） |
| `is_favorite` | BOOLEAN | 是否特别关心 |
| `last_interaction_time` | TIMESTAMP | 最后互动时间 |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |

**唯一约束**：
- `(user_id, friend_id)`（防止重复添加）

### 5.3 通知相关表

#### 5.3.1 sys_notification（通知表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `user_id` | UUID | 目标用户（系统用户ID） |
| `type` | VARCHAR(50) | 通知类型（FRIEND_REQUEST、FRIEND_RESULT、SYSTEM_ALERT 等） |
| `title` | VARCHAR(200) | 标题 |
| `content` | VARCHAR(500) | 文案内容 |
| `from_user_id` | VARCHAR(64) | 触发方用户（Keycloak userId，可选） |
| `ref_type` | VARCHAR(50) | 关联业务类型（如 "FRIEND_REQUEST"） |
| `ref_id` | UUID | 关联业务ID（如 friendRequestId） |
| `payload` | JSONB | 透传数据 |
| `actions` | TEXT[] | 可操作按钮列表（如 ["ACCEPT","REJECT"]） |
| `status` | VARCHAR(20) | 状态（UNREAD/READ/ARCHIVED/DELETED） |
| `source_service` | VARCHAR(50) | 来源服务标识 |
| `created_at` | TIMESTAMP | 创建时间 |
| `read_at` | TIMESTAMP | 已读时间 |
| `archived_at` | TIMESTAMP | 归档时间 |
| `deleted_at` | TIMESTAMP | 删除时间 |
| `updated_at` | TIMESTAMP | 更新时间 |

**索引**：
- `(user_id, status)`（用于查询和排序）
- `(user_id, ref_type, ref_id)`（用于清除操作按钮）

### 5.4 RBAC 相关表（实体已定义，业务逻辑待完善）

#### 5.4.1 sys_role（角色表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `role_code` | VARCHAR(50) | 角色编码（唯一标识） |
| `role_name` | VARCHAR(50) | 角色名称 |
| `role_desc` | VARCHAR(200) | 角色描述 |
| `data_scope` | VARCHAR(20) | 数据权限范围（ALL/DEPT/DEPT_AND_CHILD/SELF） |
| `sort_order` | INTEGER | 排序号 |
| `status` | INTEGER | 状态（0-禁用，1-启用） |
| `remark` | VARCHAR(500) | 备注 |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |
| `deleted_at` | TIMESTAMP | 软删除时间 |

#### 5.4.2 sys_permission（权限表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `permission_code` | VARCHAR(100) | 权限编码（唯一标识） |
| `permission_name` | VARCHAR(100) | 权限名称 |
| `permission_type` | VARCHAR(20) | 权限类型（MENU/BUTTON/API） |
| `resource_type` | VARCHAR(50) | 资源类型 |
| `resource_path` | VARCHAR(500) | 资源路径 |
| `http_method` | VARCHAR(10) | HTTP方法（GET/POST/PUT/DELETE等） |
| `data_expr` | JSONB | 数据权限表达式 |
| `sort_order` | INTEGER | 排序号 |
| `status` | INTEGER | 状态（0-禁用，1-启用） |
| `remark` | VARCHAR(500) | 备注 |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |
| `deleted_at` | TIMESTAMP | 软删除时间 |

#### 5.4.3 sys_user_role（用户角色关联表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `user_id` | UUID | 用户ID（复合主键） |
| `role_id` | UUID | 角色ID（复合主键） |
| `created_at` | TIMESTAMP | 创建时间 |

**复合主键**：`(user_id, role_id)`
- 使用 `@IdClass(SysUserRoleId.class)` 实现复合主键

#### 5.4.4 sys_role_permission（角色权限关联表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `role_id` | UUID | 角色ID（复合主键） |
| `permission_id` | UUID | 权限ID（复合主键） |
| `created_at` | TIMESTAMP | 创建时间 |

**复合主键**：`(role_id, permission_id)`
- 使用 `@IdClass(SysRolePermissionId.class)` 实现复合主键

---

## 六、OpenFeign 配置与使用

### 6.1 Feign 客户端定义

**文件位置**：`infrastructure/client/chat/ChatNotifyClient.java`

```java
@FeignClient(name = "chat-service", path = "/api/internal/notify", fallback = ChatNotifyClientFallback.class)
public interface ChatNotifyClient {
    @PostMapping
    @CircuitBreaker(name = "chatNotifyClient")
    void push(@RequestBody NotifyPushRequest request);
}
```

**说明**：
- **服务名**：`chat-service`
- **路径**：`/api/internal/notify`
- **熔断器**：`@CircuitBreaker(name = "chatNotifyClient")`
- **降级策略**：`ChatNotifyClientFallback`（记录日志，不抛异常）

### 6.2 降级策略

**文件位置**：`infrastructure/client/chat/ChatNotifyClientFallback.java`

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

**说明**：
- 推送失败时，仅记录 warn 日志，不抛异常
- 通知已落库，用户可离线查看，不影响主流程

### 6.3 请求 DTO

**文件位置**：`infrastructure/client/chat/dto/NotifyPushRequest.java`

```java
@Data
public class NotifyPushRequest {
    private String userId;          // 目标用户（Keycloak userId/sub）
    private String type;            // 通知类型：FRIEND_REQUEST、FRIEND_RESULT 等
    private String title;           // 标题
    private String content;         // 内容
    private String fromUserId;      // 触发方（可选）
    private Object payload;         // 透传数据
    private String[] actions;       // 可操作按钮（可选）
}
```

### 6.4 使用场景

**调用位置**：`service/notification/impl/NotificationServiceImpl.pushToChatService()`

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

**关键点**：
- **先落库后推送**：确保离线用户也能看到通知
- **推送失败不影响主事务**：通知已落库，用户可离线查看
- **携带 `notificationId`**：便于前端去重和标记已读

---

## 七、API 清单

### 7.1 用户相关 API

#### 7.1.1 获取当前用户基础信息

- **路径**：`GET /api/users/me`
- **说明**：获取当前登录用户的基础信息（仅 `sys_user`）
- **认证**：需要 JWT Token
- **响应**：`SysUser`

#### 7.1.2 获取当前用户完整信息

- **路径**：`GET /api/users/me/full`
- **说明**：获取当前登录用户的完整信息（`sys_user` + `sys_user_profile` + 预留游戏信息）
- **认证**：需要 JWT Token
- **响应**：`UserInfo`

#### 7.1.3 同步用户

- **路径**：`POST /api/users/sync`
- **说明**：从 Keycloak 同步到系统用户表（通常在用户首次登录时调用）
- **认证**：需要 JWT Token
- **请求体**：从 JWT 中获取用户信息
- **响应**：`SysUser`

#### 7.1.4 创建用户

- **路径**：`POST /api/users/save`
- **说明**：同时在 Keycloak 和系统数据库中创建用户
- **认证**：无需认证（注册接口）
- **请求体**：`CreateUserRequest`（username, nickname, password, email）
- **响应**：`SysUser`

#### 7.1.5 更新用户信息

- **路径**：`PUT /api/users/update/{userId}`
- **说明**：更新用户信息（昵称、密码、邮箱），同步 Keycloak
- **认证**：需要 JWT Token
- **请求体**：`UpdateUserRequest`（nickname, password, email）
- **响应**：`SysUser`

#### 7.1.6 删除用户

- **路径**：`DELETE /api/users/delete/{userId}`
- **说明**：软删除用户（禁用 Keycloak 用户，软删除系统数据库用户）
- **认证**：需要 JWT Token
- **响应**：`Result<Void>`

#### 7.1.7 批量获取用户信息

- **路径**：`POST /api/users/users/batch`
- **说明**：根据 Keycloak 用户ID列表批量获取用户信息
- **认证**：需要 JWT Token
- **请求体**：`List<String>`（Keycloak 用户ID列表）
- **响应**：`List<UserInfo>`

#### 7.1.8 根据单个 Keycloak 用户ID获取用户信息

- **路径**：`GET /api/users/users/{userId}`
- **说明**：根据单个 Keycloak 用户ID获取用户信息
- **认证**：需要 JWT Token
- **响应**：`UserInfo`

#### 7.1.9 获取当前用户资料

- **路径**：`GET /api/users/me/profile`
- **说明**：获取当前用户的完整资料（`UserInfo`）
- **认证**：需要 JWT Token
- **响应**：`UserInfo`

#### 7.1.10 更新当前用户资料

- **路径**：`PUT /api/users/me/profile`
- **说明**：更新当前用户资料（包括 `sys_user` 和 `sys_user_profile`）
- **认证**：需要 JWT Token
- **请求体**：`UpdateProfileRequest`（nickname, email, phone, avatarUrl, bio, locale, timezone, settings）
- **响应**：`UserInfo`

#### 7.1.11 根据用户ID查询（系统用户ID）

- **路径**：`GET /api/users/get/{userId}`
- **说明**：根据系统用户ID查询用户信息（仅 `sys_user`）
- **认证**：需要 JWT Token
- **路径参数**：`userId`（系统用户ID，UUID格式）
- **响应**：`SysUser`
- **注意**：主要用于内部调试/兼容，通常使用 Keycloak 用户ID查询

#### 7.1.12 根据用户名查询

- **路径**：`GET /api/users/username/{username}`
- **说明**：根据用户名查询用户信息（仅 `sys_user`）
- **认证**：需要 JWT Token
- **路径参数**：`username`（用户名）
- **响应**：`SysUser`

#### 7.1.13 兼容接口（已废弃）

- **路径**：`POST /api/users/players/batch`、`GET /api/users/players/{userId}`
- **说明**：兼容旧接口，重定向到新接口
- **状态**：`@Deprecated`

### 7.2 好友相关 API

#### 7.2.1 申请加好友

- **路径**：`POST /api/friends/apply`
- **说明**：申请加好友，支持双向申请自动通过
- **认证**：需要 JWT Token
- **请求体**：`ApplyFriendRequest`（targetUserId, requestMessage）
- **响应**：`ApiResponse<Void>`
- **业务逻辑**：
  - 如果存在反向的待处理申请，自动通过并返回 `"已自动成为好友"`
  - 否则创建新的申请记录并返回 `"申请已发送，等待对方处理"`

#### 7.2.2 同意好友申请

- **路径**：`POST /api/friends/requests/{id}/accept`
- **说明**：同意好友申请（接收方操作）
- **认证**：需要 JWT Token
- **路径参数**：`id`（好友申请ID）
- **响应**：`ApiResponse<Void>`
- **业务逻辑**：
  - 更新申请状态为 `ACCEPTED`
  - 创建双向好友关系
  - 清除接收方通知的操作按钮
  - 通知申请人结果

#### 7.2.3 拒绝好友申请

- **路径**：`POST /api/friends/requests/{id}/reject`
- **说明**：拒绝好友申请（接收方操作）
- **认证**：需要 JWT Token
- **路径参数**：`id`（好友申请ID）
- **响应**：`ApiResponse<Void>`
- **业务逻辑**：
  - 更新申请状态为 `REJECTED`
  - 清除接收方通知的操作按钮
  - 通知申请人结果

#### 7.2.4 获取好友列表

- **路径**：`GET /api/friends`
- **说明**：获取当前用户的好友列表
- **认证**：需要 JWT Token
- **响应**：`List<FriendInfo>`
- **业务逻辑**：
  - 查询当前用户的所有好友关系（状态为 `ACTIVE`）
  - 批量查询好友的完整用户信息（`UserInfo`）
  - 组装 `FriendInfo` 列表

#### 7.2.5 检查好友关系

- **路径**：`GET /api/friends/check/{userId1}/{userId2}`
- **说明**：检查两个用户是否是好友关系（用于私聊等功能的前置验证）
- **认证**：需要 JWT Token
- **路径参数**：`userId1`、`userId2`（Keycloak 用户ID）
- **响应**：`Boolean`

### 7.3 通知相关 API

#### 7.3.1 查询通知列表

- **路径**：`GET /api/notifications`
- **说明**：查询通知列表（支持状态过滤，未读优先排序）
- **认证**：需要 JWT Token
- **查询参数**：
  - `status`（可选）：状态过滤（UNREAD/READ）
  - `limit`（默认 10，最大 100）：限制条数
- **响应**：`List<NotificationView>`
- **排序规则**：未读优先，时间倒序

#### 7.3.2 未读数量

- **路径**：`GET /api/notifications/unread-count`
- **说明**：统计未读通知数量
- **认证**：需要 JWT Token
- **响应**：`Long`

#### 7.3.3 标记单条已读

- **路径**：`POST /api/notifications/{id}/read`
- **说明**：标记单条通知为已读（幂等）
- **认证**：需要 JWT Token
- **路径参数**：`id`（通知ID）
- **响应**：`Result<Void>`
- **权限校验**：`userId` 必须匹配

#### 7.3.4 全部标记已读

- **路径**：`POST /api/notifications/read-all`
- **说明**：批量标记所有未读通知为已读（最多处理 500 条）
- **认证**：需要 JWT Token
- **响应**：`Result<Void>`

#### 7.3.5 通知类型元数据

- **路径**：`GET /api/notifications/metadata`
- **说明**：返回支持的通知类型元数据，便于前端展示/提示
- **认证**：无需认证
- **响应**：`List<NotificationTypeMetadata>`
- **包含类型**：
  - `FRIEND_REQUEST`：可操作（ACCEPT、REJECT）
  - `FRIEND_RESULT`：不可操作
  - `SYSTEM_ALERT`：不可操作

### 7.4 文件相关 API

#### 7.4.1 上传头像到临时目录

- **路径**：`POST /api/files/upload/avatar/temp`
- **说明**：上传头像到临时目录（用于完善用户信息）
- **认证**：需要 JWT Token
- **请求体**：`multipart/form-data`（file）
- **响应**：`Result<String>`（临时文件访问 URL）
- **文件验证**：只允许 JPG、JPEG、PNG、GIF、WEBP，最大 2MB
- **文件命名**：`{userId}.{ext}`

#### 7.4.2 上传头像（旧接口，已废弃）

- **路径**：`POST /api/files/upload/avatar`
- **说明**：旧方法，使用UUID作为文件名，上传到正式目录（兼容旧接口）
- **认证**：无需认证（暂时放开权限，方便测试）
- **状态**：`@Deprecated`

#### 7.4.3 上传游戏回放

- **路径**：`POST /api/files/upload/replay`
- **说明**：上传游戏回放
- **认证**：无需认证（暂时放开权限，方便测试）
- **请求体**：`multipart/form-data`（file, gameType, roomId）
- **响应**：`Result<String>`（文件访问 URL）
- **文件路径**：`{gameType}/{roomId}/replay.json`

#### 7.4.4 上传活动素材

- **路径**：`POST /api/files/upload/material`
- **说明**：上传活动素材
- **认证**：无需认证（暂时放开权限，方便测试）
- **请求体**：`multipart/form-data`（file, type, id）
- **响应**：`Result<String>`（文件访问 URL）
- **文件路径**：`{type}/{id}/{originalFilename}`

### 7.5 认证相关 API

#### 7.5.1 获取 Token

- **路径**：`POST /api/auth/token`
- **说明**：获取 JWT Token（密码模式，仅用于开发和测试）
- **认证**：无需认证
- **请求体**：`LoginRequest`（username, password）
- **响应**：`TokenResponse`（accessToken, refreshToken, expiresIn 等）
- **注意**：生产环境建议使用授权码模式

### 7.6 内部接口

#### 7.6.1 Keycloak 事件回调

- **路径**：`POST /internal/keycloak/events`、`POST /internal/keycloak/events/**`
- **说明**：接收来自 Keycloak 的事件 Webhook（来自 vymalo/keycloak-webhook 插件）
- **认证**：Basic Auth（`event-webhook-basic-username` + `event-webhook-basic-password`）
- **请求体**：JSON（事件负载）
- **响应**：`Result<Void>`
- **处理事件**：只处理 `REGISTER` 事件，自动创建本地用户

#### 7.6.2 会话监控

- **路径**：`GET /internal/sessions`
- **说明**：获取所有在线用户的会话信息（包含用户昵称），用于开发和调试
- **认证**：无需认证（完全开放）
- **CORS**：支持跨域（`@CrossOrigin(origins = "*")`）
- **响应**：`List<UserSessionSnapshotWithUserInfo>`
- **业务逻辑**：
  - 从 `SessionRegistry` 获取所有用户会话
  - 批量查询用户信息并填充昵称、用户名
  - 返回会话快照列表（带用户信息）

---

## 八、前端交互

### 8.1 用户管理

#### 8.1.1 用户同步

**前端调用**：`POST /api/users/sync`

**场景**：用户首次登录时，前端调用此接口同步用户信息到系统数据库

**流程**：
1. 前端从 JWT 中获取用户信息
2. 调用 `/api/users/sync` 同步用户
3. 如果返回 404，提示用户不存在

#### 8.1.2 用户资料更新

**前端调用**：`PUT /api/users/me/profile`

**场景**：用户在个人资料页面更新资料

**流程**：
1. 上传头像到临时目录（`POST /api/files/upload/avatar/temp`）
2. 调用 `PUT /api/users/me/profile` 更新资料（头像URL会自动从临时目录移动到正式目录）
3. 刷新用户信息缓存

### 8.2 好友系统

#### 8.2.1 好友申请流程

**前端调用**：
1. `POST /api/friends/apply`：申请加好友
2. WebSocket 接收通知：`/user/queue/notify`（类型为 `FRIEND_REQUEST`）
3. `POST /api/friends/requests/{id}/accept` 或 `POST /api/friends/requests/{id}/reject`：处理申请
4. WebSocket 接收通知：`/user/queue/notify`（类型为 `FRIEND_RESULT`）

**前端事件**：
- 申请通过后，触发 `gh-friend-list-refresh` 事件，自动刷新好友列表

**详细流程**：
1. 用户A申请加用户B为好友
2. 后端创建申请记录，落库通知，通过 Feign 调用 chat-service 推送通知
3. 用户B通过 WebSocket 收到通知，显示"同意"/"拒绝"按钮
4. 用户B点击"同意"，前端调用 `POST /api/friends/requests/{id}/accept`
5. 后端创建双向好友关系，清除通知操作按钮，通知申请人结果
6. 用户A通过 WebSocket 收到结果通知
7. 前端触发 `gh-friend-list-refresh` 事件，自动刷新好友列表

#### 8.2.2 好友列表查询

**前端调用**：`GET /api/friends`

**场景**：在私聊页面或好友列表页面显示好友

**响应**：`List<FriendInfo>`（包含好友的完整用户信息 `UserInfo`）

### 8.3 通知系统

#### 8.3.1 通知查询

**前端调用**：
1. `GET /api/notifications?limit=10`：查询通知列表（未读优先）
2. `GET /api/notifications/unread-count`：获取未读数量

**场景**：在通知中心页面（`MessageCenterPage.jsx`）或通知铃铛（`Header.jsx`）显示通知

**排序**：前端本地二次排序（未读优先、时间倒序）确保操作后无需刷新

#### 8.3.2 通知操作

**前端调用**：
1. `POST /api/notifications/{id}/read`：标记单条已读
2. `POST /api/notifications/read-all`：全部标记已读
3. `POST /api/friends/requests/{id}/accept` 或 `POST /api/friends/requests/{id}/reject`：处理好友申请

**场景**：
- 点击通知：默认仅标记已读
- 好友申请（`type=FRIEND_REQUEST`，`actions` 包含 ACCEPT/REJECT）：渲染"同意"/"拒绝"按钮，调用后端 accept/reject，成功后标记已读、移除动作，未读数减 1

#### 8.3.3 WebSocket 通知推送

**前端订阅**：`/user/queue/notify`（通过 `chatSocket.js`）

**消息格式**：
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

**前端处理**：
1. 接收通知，追加到通知列表（去重）
2. 未读优先排序，截断 10 条
3. 未读计数对未读新消息 +1
4. 触发 `gh-notify` 自定义事件，通知其他组件

**详细流程**：
1. 后端通过 Feign 调用 chat-service 推送通知
2. chat-service 通过 WebSocket 推送到前端
3. 前端 `chatSocket.js` 接收通知，调用 `notifyListeners`
4. `notifyListeners` 分发通知，触发 `gh-notify` 事件
5. `Header.jsx` 和 `MessageCenterPage.jsx` 监听 `gh-notify` 事件，更新通知列表和未读计数

### 8.4 文件上传

#### 8.4.1 头像上传流程

**前端调用**：
1. `POST /api/files/upload/avatar/temp`：上传头像到临时目录
2. `PUT /api/users/me/profile`：更新用户资料（头像URL会自动从临时目录移动到正式目录）

**场景**：用户在个人资料页面上传头像

**流程**：
1. 前端选择图片文件
2. 调用 `POST /api/files/upload/avatar/temp`，返回临时URL
3. 前端预览临时URL
4. 用户确认后，调用 `PUT /api/users/me/profile`，传入临时URL
5. 后端自动将临时文件移动到正式目录，规范化文件名为 `{userId}.{ext}`
6. 返回正式URL

---

## 九、扩展性分析

### 9.1 通知系统扩展性

#### 9.1.1 扩展性较好的部分 ✅

**数据模型通用性强**：
- `Notification` 实体字段设计合理：
  - `type`：通知类型标识，支持任意字符串
  - `actions`：操作按钮列表（`List<String>`），可定义任意操作
  - `payload`：透传数据（`Map<String, Object>`），可存储任意业务数据
  - `refType` + `refId`：关联业务类型和ID，便于去重和状态查询
- **优势**：新增通知类型无需修改数据库结构

**通用服务方法完善**：
- `listNotifications()`：查询通知列表（支持状态过滤）
- `markRead()`：标记已读（带权限校验）
- `markAllRead()`：批量标记已读
- `countUnread()`：统计未读数量
- `clearNotificationActions()`：清除操作按钮（通用，通过 refType/refId 匹配）
- **优势**：这些方法对所有通知类型都适用

**元数据机制已建立**：
- `NotificationTypeMetadata`：定义通知类型的元信息
- `/api/notifications/metadata`：返回所有支持的通知类型配置
- **优势**：前端可以通过元数据了解通知类型的特性

#### 9.1.2 扩展性不足的部分 ❌

**后端服务层硬编码**：
- **问题1**：类型专用方法
  ```java
  void notifyFriendRequest(...);  // 专门针对好友申请
  void notifyFriendResult(...);   // 专门针对好友申请结果
  ```
- **问题2**：状态查询逻辑硬编码
  ```java
  // NotificationServiceImpl.toView()
  if ("FRIEND_REQUEST".equals(n.getType()) 
      && (n.getActions() == null || n.getActions().isEmpty()) 
      && n.getRefId() != null) {
      // 硬编码查询 FriendRequest 状态
      friendRequestRepository.findById(n.getRefId())...;
  }
  ```
- **影响**：新增可操作通知类型需要：
  1. 在接口中新增专用方法
  2. 在实现类中实现该方法
  3. 在 `toView()` 中增加对应的状态查询逻辑
  4. 需要注入对应的 Repository

**前端处理逻辑硬编码**：
- **问题1**：操作处理函数硬编码
  ```javascript
  const handleFriendRequestAction = async (item, action, e) => {
    if (action === 'ACCEPT') {
      await acceptFriendRequest(requestId)
    } else if (action === 'REJECT') {
      await rejectFriendRequest(requestId)
    }
  }
  ```
- **问题2**：渲染逻辑硬编码
  ```javascript
  const isFriendRequest = item.type === 'FRIEND_REQUEST'
  {isFriendRequest && actions.length > 0 && (
    // 硬编码按钮文案"同意"/"拒绝"
  )}
  ```
- **问题3**：未使用元数据机制
  - 前端没有调用 `/api/notifications/metadata` 来动态渲染
  - 按钮文案、样式都是硬编码
- **影响**：新增可操作通知类型需要：
  1. 新增对应的处理函数
  2. 修改渲染逻辑，增加新的类型判断
  3. 硬编码新的按钮文案和样式
  4. 新增对应的 API 调用函数

#### 9.1.3 扩展成本评估

**场景1：新增简单通知（无需操作）**
- **示例**：系统维护通知、活动公告等
- **需要的工作**：
  - ✅ 后端：调用通用方法创建通知（无需新增方法）
  - ✅ 前端：自动渲染，无需修改代码
  - ✅ 元数据：在 `metadata()` 中注册新类型
- **成本**：**低** ⭐

**场景2：新增可操作通知**
- **示例**：游戏邀请（GAME_INVITE，支持 ACCEPT/REJECT）
- **需要的工作**：
  - **后端**：
    1. 在 `NotificationService` 接口新增 `notifyGameInvite()` 方法
    2. 在 `NotificationServiceImpl` 实现该方法
    3. 在 `toView()` 中增加游戏邀请的状态查询逻辑（硬编码）
    4. 在 `NotificationController.metadata()` 中注册新类型
    5. 注入 `GameInviteRepository`（如果需要查询状态）
  - **前端**：
    1. 新增 `handleGameInviteAction()` 函数
    2. 修改渲染逻辑，增加 `isGameInvite` 判断
    3. 硬编码新的按钮文案和样式
    4. 新增对应的 API 调用函数（`acceptGameInvite`、`rejectGameInvite`）
- **成本**：**中等** ⭐⭐⭐

**场景3：新增复杂可操作通知**
- **示例**：团队邀请（TEAM_INVITE，支持 ACCEPT/REJECT/VIEW_DETAIL）
- **需要的工作**：同场景2，但操作更复杂，可能需要多个 API 调用，前端状态管理更复杂
- **成本**：**中高** ⭐⭐⭐⭐

#### 9.1.4 改进建议

**短期（1-2个迭代）**：
1. **前端使用元数据**：改造前端渲染逻辑，使用 metadata 动态渲染按钮
2. **统一操作处理**：建立 action handler 映射表
3. **按钮文案配置化**：从 metadata 或 payload 获取文案

**中期（3-6个迭代）**：
1. **后端状态查询策略化**：实现 `NotificationStatusResolver` 机制
2. **通用通知方法**：提供通用的 `notify()` 方法
3. **统一操作 API**：后端提供 `/api/notifications/{id}/actions/{action}` 接口

**长期（6个月以上）**：
1. **全面重构为插件化架构**：实现 Handler 机制
2. **完善文档和示例**：提供新增通知类型的完整指南

**预期效果**：
- **短期**：新增简单通知类型成本降低 50%
- **中期**：新增可操作通知类型成本降低 70%
- **长期**：新增通知类型成本降低 90%，完全解耦

#### 9.1.5 详细改进方案

> **注意**：以下为改进建议，并非当前实现。当前实现见 4.3 节。

**方案A：渐进式改进（推荐）**

适合当前阶段，在保持现有功能的基础上逐步优化。

**后端改进**：

1. **抽象通用通知方法**：
```java
// NotificationService.java
/**
 * 通用通知创建方法
 */
void notify(String type, UUID userId, String title, String content, 
            List<String> actions, Map<String, Object> payload);
```

2. **状态查询策略化**：
```java
// 定义状态查询接口
interface NotificationStatusResolver {
    boolean supports(String type);
    Map<String, Object> resolveStatus(UUID refId);
}

// 注册多个实现
private final Map<String, NotificationStatusResolver> statusResolvers;

// toView() 中使用
if (statusResolvers.containsKey(n.getType())) {
    Map<String, Object> status = statusResolvers.get(n.getType())
        .resolveStatus(n.getRefId());
    payload.putAll(status);
}
```

**前端改进**：

1. **使用元数据动态渲染**：
```javascript
// 初始化时获取元数据
const [metadata, setMetadata] = useState({})
useEffect(() => {
  fetchNotificationMetadata().then(data => {
    const map = {}
    data.forEach(m => { map[m.type] = m })
    setMetadata(map)
  })
}, [])

// 动态渲染按钮
{metadata[item.type]?.actionable && actions.length > 0 && (
  <div className="notify-actions">
    {actions.map(action => (
      <button onClick={(e) => handleAction(item, action, e)}>
        {getActionLabel(item.type, action)}
      </button>
    ))}
  </div>
)}
```

2. **统一操作处理**：
```javascript
// 建立 action handler 映射表
const actionHandlers = {
  'FRIEND_REQUEST': {
    'ACCEPT': (item) => acceptFriendRequest(item.payload.friendRequestId),
    'REJECT': (item) => rejectFriendRequest(item.payload.friendRequestId)
  },
  'GAME_INVITE': {
    'ACCEPT': (item) => acceptGameInvite(item.payload.gameInviteId),
    'REJECT': (item) => rejectGameInvite(item.payload.gameInviteId)
  }
}

const handleAction = async (item, action, e) => {
  const handler = actionHandlers[item.type]?.[action]
  if (handler) {
    await handler(item)
  }
}
```

**方案B：全面重构（长期）**

适合通知类型较多、需要高度灵活性的场景。

**核心思想**：插件化通知处理器

```
NotificationHandler (接口)
  ├── FriendRequestHandler
  ├── GameInviteHandler
  └── TeamInviteHandler
```

**实现要点**：
1. 每个通知类型对应一个 Handler
2. Handler 负责：创建通知、查询状态、处理操作
3. 通过 Spring 的 `ApplicationContext` 自动注册 Handler
4. 前端通过 metadata 动态渲染，通过统一 API 处理操作

**后端实现示例**：
```java
// 通知处理器接口
interface NotificationHandler {
    String getType();
    void notify(NotificationContext context);
    Map<String, Object> resolveStatus(UUID refId);
    void handleAction(String action, UUID refId, UUID userId);
}

// 好友申请处理器
@Component
class FriendRequestHandler implements NotificationHandler {
    @Override
    public String getType() { return "FRIEND_REQUEST"; }
    
    @Override
    public void notify(NotificationContext context) {
        // 创建好友申请通知
    }
    
    @Override
    public Map<String, Object> resolveStatus(UUID refId) {
        // 查询好友申请状态
    }
    
    @Override
    public void handleAction(String action, UUID refId, UUID userId) {
        // 处理同意/拒绝
    }
}
```

**前端实现示例**：
```javascript
// 统一操作 API
POST /api/notifications/{id}/actions/{action}

// 前端统一处理
const handleAction = async (item, action) => {
  await post(`/api/notifications/${item.id}/actions/${action}`)
}
```

#### 9.1.6 已知风险与改进建议

**当前实现存在的风险**：

1. **API 前缀硬编码**：
   - 问题：前端 accept/reject 仅尝试 `/system-service/api/...`、`/api/...` 两条路径，若部署前缀不同需改为可配置
   - 建议：将 API 前缀配置化，从配置文件或环境变量读取

2. **分页/历史缺失**：
   - 问题：通知仅取最新 10 条，无分页/加载更多，历史不可见
   - 建议：支持分页查询，提供"加载更多"功能，支持查看历史通知

3. **未读计数一致性**：
   - 问题：本地未读数更新与后端可能短暂不一致（处理失败时）
   - 建议：在处理成功后重新拉取 `unread-count` 兜底，确保一致性

4. **推送去重依赖 id**：
   - 问题：WebSocket 推送依赖 `notificationId` 进行去重；若缺失 `notificationId`，前端会使用 `friendRequestId` 或时间戳作为 fallback，但可能不够精确
   - 建议：确保所有 WebSocket 推送都携带 `notificationId`，前端已实现基于 `notificationId` 的去重逻辑（优先使用 `notificationId`，fallback 到 `refType + refId` 组合）

5. **安全问题**：
   - 问题：内部推送接口仅 JWT，无额外签名/白名单，需确保只经网关受控暴露
   - 建议：在生产环境中添加 IP 白名单或签名验证机制

### 9.2 好友系统扩展性

**当前实现**：
- 支持双向申请自动通过
- 支持好友备注、分组、特别关心
- 支持好友关系检查

**扩展性**：
- ✅ 数据模型通用性强：`UserFriend` 表支持扩展字段（`friend_nickname`、`friend_group`、`is_favorite`）
- ✅ 业务逻辑清晰：申请、同意/拒绝、列表查询、关系检查分离
- ⚠️ 缺少好友分组管理 API（当前仅支持字段存储）
- ⚠️ 缺少好友备注更新 API（当前仅支持字段存储）

**改进建议**：
- 新增好友分组管理 API（创建、删除、重命名分组）
- 新增好友备注更新 API
- 支持好友关系状态扩展（如：BLOCKED 拉黑功能）

### 9.3 用户管理扩展性

**当前实现**：
- 支持用户同步、创建、更新、删除
- 支持用户资料管理（基础信息 + 扩展信息）
- 支持用户信息缓存

**扩展性**：
- ✅ 数据模型分层清晰：`sys_user`（基础信息）+ `sys_user_profile`（扩展信息）
- ✅ 缓存策略完善：Redis 缓存，TTL 2小时，主动刷新/失效
- ✅ Keycloak 双向同步：昵称、邮箱同步更新
- ⚠️ 缺少用户搜索 API（当前仅支持精确查询）
- ⚠️ 缺少用户列表分页查询 API

**改进建议**：
- 新增用户搜索 API（支持用户名、昵称模糊搜索）
- 新增用户列表分页查询 API（支持排序、过滤）

---

## 十、总结

### 10.1 核心功能

system-service 作为系统域核心服务，提供了完整的用户管理、好友系统、通知系统、文件存储等功能，并与 Keycloak 深度集成，实现了用户的双向同步。

### 10.2 技术亮点

1. **用户信息缓存**：Redis 缓存，TTL 2小时，主动刷新/失效，提升查询性能
2. **双向申请自动通过**：提升用户体验
3. **通知先落库后推送**：确保离线用户也能看到通知
4. **Keycloak 事件监听**：自动创建本地用户，减少手动同步
5. **头像规范化**：统一文件名为 `{userId}.{ext}`，便于管理

### 10.3 扩展性

- **通知系统**：数据模型通用性强，但业务逻辑层存在硬编码，建议逐步优化为插件化架构
- **好友系统**：数据模型通用性强，业务逻辑清晰，缺少部分管理 API
- **用户管理**：数据模型分层清晰，缓存策略完善，缺少搜索和列表查询 API

### 10.4 待完善功能

1. **RBAC 权限系统**：实体已定义，业务逻辑待完善
2. **用户搜索**：支持用户名、昵称模糊搜索
3. **用户列表**：支持分页查询、排序、过滤
4. **好友分组管理**：创建、删除、重命名分组 API
5. **好友备注更新**：更新好友备注 API

---

**文档维护**：建议在新增功能或修改实现时更新本文档，确保文档与代码 100% 一致。
