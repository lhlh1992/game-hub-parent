# Session Common

统一会话管理库：同时管理 JWT 登录会话与 WebSocket/WebFlux 长连接，可用于后台查看在线用户、强制下线等场景。

## 功能特性

- 登录会话管理：记录 / 查询 / 清理用户的 JWT / Token
- WebSocket 会话管理：记录 / 查询 / 清理长连接
- 聚合视图：获取用户所有会话快照，统计在线数量
- 强制下线支持：批量清理登录 + WebSocket 会话，便于执行黑名单及断开连接
- Redis 持久化：独立 Redis 库，多服务协同

## 快速开始

> 注意：库已通过 Spring Boot AutoConfiguration 自动注册 `SessionRedisConfig`（Spring Boot 2.7+/3.x 支持 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 机制），只需添加依赖并配置 `session.redis.*` 即可。
>
> 如果项目仍停留在 2.6 及之前版本（尚未支持 `AutoConfiguration.imports`），请改用传统方式：
> - 在应用侧手动 `@ComponentScan("com.gamehub.session")` 或 `@Import(SessionRedisConfig.class)`；
> - 或者在库中额外提供 `META-INF/spring.factories` 映射。

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.gamehub</groupId>
    <artifactId>session-common</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置专用 Redis

```yaml
session:
  redis:
    host: localhost
    port: 6379
    database: 15
    password: ""
```

### 3. 在业务中使用

1. **登录/登出（通常在 gateway）**
   - 登录成功：`registerLoginSessionEnforceSingle(loginInfo, ttl)`，对返回的旧 token 写黑名单。
   - 登出：`unregisterLoginSession(tokenId)`。
2. **WebSocket 服务（如 game-service）**
   - 握手成功：`registerWebSocketSessionEnforceSingle(wsInfo, 0)`，对返回的旧会话发送踢人/断开通知。
   - 断开事件：`unregisterWebSocketSession(sessionId)`。
3. **后台管理**
   - 查询在线用户：`getAllUserSessions()`。
   - 强制下线：`removeAllSessions(userId)`，结果中包含被清理的登录会话和 WS 会话。

### 自动配置机制说明

- 自动装配入口文件：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 每一行填写一个需要自动加载的配置类全限定名，例如：`com.gamehub.session.config.SessionRedisConfig`
- 新增配置类时，继续追加在该文件中（换行即可），Spring Boot 启动时会按行扫描并导入
- 仅在 Spring Boot 2.7+ / 3.x 生效；低版本需改用 `spring.factories` 或手动 `@Import`

## Redis 数据结构

```
session:login:user:{userId}     -> Set<tokenId>
session:login:token:{tokenId}   -> LoginSessionInfo JSON
session:ws:user:{userId}        -> Set<sessionId>
session:ws:session:{sessionId}  -> WebSocketSessionInfo JSON
```

## API 列表

| 方法 | 说明 |
|------|------|
| `registerLoginSession(info, ttlSeconds)` | 注册 / 续期登录会话 |
| `unregisterLoginSession(sessionId)` | 注销登录会话 |
| `getLoginSessions(userId)` | 查询登录会话列表 |
| `removeAllLoginSessions(userId)` | 清理登录会话并返回 |
| `registerWebSocketSession(info, ttlSeconds)` | 注册 WebSocket 会话 |
| `unregisterWebSocketSession(sessionId)` | 注销 WebSocket 会话 |
| `getWebSocketSessions(userId)` | 查询 WebSocket 会话列表 |
| `removeAllWebSocketSessions(userId)` | 清理 WebSocket 会话并返回 |
| `getUserSessions(userId)` | 获取聚合快照 |
| `removeAllSessions(userId)` | 清理所有会话并返回快照 |
| `getAllUserSessions()` | 获取全部在线用户快照 |
| `hasAnySession(userId)` | 判断用户是否在线 |

## 注意事项

1. Token 存储：示例直接保存原始 Token，生产环境建议存储摘要。
2. TTL 设置：登录会话 TTL 建议与 Token 过期时间一致；WebSocket 默认 24 小时。
3. KEYS 命令：`getAllUserSessions()` 当前使用 `KEYS`，适合用户量较小，可按需替换为 `SCAN`。
4. 职责划分：该库负责数据读写，后台管理接口可由 system-service 封装。
