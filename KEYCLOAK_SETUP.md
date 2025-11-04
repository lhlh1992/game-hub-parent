# Keycloak 配置指南

## 1. 启动服务

### 使用 Docker Compose 启动所有服务

```bash
# 在工作空间根目录（d:\workspace\java）执行
docker-compose up -d
```

这会启动以下服务：
- **PostgreSQL** (端口 5432) - Keycloak 数据存储
- **Redis** (端口 6379) - 缓存服务
- **Keycloak** (端口 8180) - 认证服务器
- **Gateway** (端口 8080) - API 网关

### 查看服务状态

```bash
# 查看所有服务状态
docker-compose ps

# 查看服务日志
docker-compose logs -f gateway    # Gateway 日志
docker-compose logs -f keycloak   # Keycloak 日志
```

### 访问服务

等待 Keycloak 启动完成（约 1-2 分钟），访问：
- **Keycloak Admin Console**: http://localhost:8180
  - 默认管理员账号: `admin` / `admin`
- **Gateway**: http://localhost:8080
  - 健康检查: http://localhost:8080/actuator/health

---

## 2. 创建 Realm

1. 登录 Keycloak Admin Console（http://localhost:8180）
2. 鼠标悬停在左上角 `Master` Realm，点击 `Create Realm`
3. 填写 Realm 名称：`gamehub`
4. 点击 `Create`

---

## 3. 创建 Client（客户端）

1. 在左侧菜单选择 `Clients`
2. 点击 `Create client`
3. 填写以下信息：
   - **Client type**: `OpenID Connect`
   - **Client ID**: `game-platform`
4. 点击 `Next`
5. **Capability config** 页面：
   - 勾选 `Client authentication`: `Off`（公共客户端，前端使用）
   - 勾选 `Authorization`: `Off`（如果不需要细粒度权限控制）
   - 勾选 `Standard flow`: `On`（授权码流程）
   - 勾选 `Direct access grants**: `On`（密码模式，用于用户名密码登录）
6. 点击 `Next`
7. **Login settings** 页面：
   - **Valid redirect URIs**: `http://localhost:3000/*`（前端应用地址，可按需调整）
   - **Web origins**: `*`（开发环境，生产环境应限制域名）
   - **Post logout redirect URIs**: `http://localhost:3000/*`
8. 点击 `Save`

---

## 4. 创建 Roles（角色）

1. 在左侧菜单选择 `Realm roles`
2. 点击 `Create role`
3. 依次创建以下角色：
   - `PLAYER` - 普通玩家
   - `GUEST` - 游客
   - `ADMIN` - 管理员

---

## 5. 创建测试用户

### 创建用户

1. 在左侧菜单选择 `Users`
2. 点击 `Create new user`
3. 填写信息：
   - **Username**: `testuser`
   - **Email**: `test@example.com`（可选）
   - **Email verified**: `On`
   - **First name**: `Test`（可选）
   - **Last name**: `User`（可选）
4. 点击 `Create`

### 设置密码

1. 点击 `Credentials` 标签
2. 设置密码（如 `password123`）
3. 关闭 `Temporary`（避免首次登录要求修改密码）
4. 点击 `Set password`，确认

### 分配角色

1. 点击 `Role mapping` 标签
2. 点击 `Assign role`
3. 选择 `Filter by clients` → 选择 `game-platform`（如果需要客户端角色）
   或选择 `Filter by realm roles` → 选择 `PLAYER`（Realm 级别角色）
4. 点击 `Assign`

---

## 6. 配置游客 Token（可选）

如果需要生成游客 Token，有几种方案：

### 方案A：使用 Client Credentials 模式

1. 创建一个新的 Client，设置 `Client authentication: On`
2. 在 `Credentials` 标签获取 `Client secret`
3. 使用 Client Credentials 模式获取 Token，然后通过 Admin API 创建临时用户

### 方案B：在 Gateway 提供 `/auth/guest` 端点

在 Gateway 中创建一个端点，调用 Keycloak Admin API 生成临时用户和 Token。

---

## 7. 验证配置

### 获取 Access Token（密码模式）

```bash
curl -X POST http://localhost:8180/auth/realms/gamehub/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=game-platform" \
  -d "username=testuser" \
  -d "password=password123"
```

响应示例：
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "...",
  "token_type": "Bearer",
  "not-before-policy": 0,
  "session_state": "...",
  "scope": "profile email"
}
```

### 验证 Token（通过 Gateway）

```bash
# 使用获取到的 access_token
curl -X GET http://localhost:8080/api/game/test \
  -H "Authorization: Bearer <access_token>"
```

如果 Gateway 正确配置，应该会：
1. ✅ 验证 JWT Token（从 Keycloak 验证签名和有效期）
2. ✅ 提取用户信息并注入到请求头：`X-User-Id`, `X-Username`, `X-Roles`
3. ✅ 转发请求到下游服务（如 game-service）

### 验证 Gateway 用户信息注入

为了验证 Gateway 是否正确注入用户信息，可以创建一个测试端点或查看 Gateway 日志：

```bash
# 查看 Gateway 日志（应能看到用户信息提取的日志）
docker-compose logs gateway | grep "JWT 用户信息"
```

Gateway 会在日志中输出类似：
```
JWT 用户信息 - userId: f:xxx:testuser, username: testuser, roles: PLAYER
```

---

## 8. Keycloak JWT Token 字段说明

Keycloak 签发的 JWT 包含以下标准字段：

| 字段 | 说明 | 在 Gateway Filter 中的处理 |
|------|------|---------------------------|
| `sub` | 用户唯一标识（userId） | → `X-User-Id` |
| `preferred_username` | 用户名 | → `X-Username` |
| `realm_access.roles` | Realm 级别角色数组 | → `X-Roles`（逗号分隔） |
| `resource_access.{client-id}.roles` | 客户端级别角色数组 | 可按需提取 |
| `exp` | 过期时间（Unix 时间戳） | Spring Security 自动验证 |
| `iss` | 签发者（Realm URI） | Spring Security 自动验证 |

---

## 9. 前端集成示例

### 方式1：直接跳转 Keycloak 登录页

```javascript
// 构建授权 URL
const authUrl = `http://localhost:8180/auth/realms/gamehub/protocol/openid-connect/auth?` +
  `client_id=game-platform&` +
  `redirect_uri=http://localhost:3000/callback&` +
  `response_type=code&` +
  `scope=openid profile email`;

// 跳转到登录页
window.location.href = authUrl;
```

### 方式2：使用密码模式（用户名密码登录）

```javascript
const response = await fetch('http://localhost:8180/auth/realms/gamehub/protocol/openid-connect/token', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/x-www-form-urlencoded',
  },
  body: new URLSearchParams({
    grant_type: 'password',
    client_id: 'game-platform',
    username: 'testuser',
    password: 'password123'
  })
});

const data = await response.json();
const accessToken = data.access_token;
```

---

## 10. 常见问题

### Q: Gateway 无法验证 JWT？

**A:** 检查：
1. **Keycloak 是否已启动并健康**
   ```bash
   docker-compose ps keycloak
   curl http://localhost:8180/health/ready
   ```

2. **Gateway 的 `issuer-uri` 是否正确**
   - 容器环境：`http://keycloak:8180/auth/realms/gamehub`
   - 本地环境：`http://localhost:8180/auth/realms/gamehub`
   - 检查 Gateway 日志中的配置信息

3. **Realm 名称是否一致**
   - Keycloak 中创建的 Realm 名称必须是 `gamehub`
   - Gateway 配置中的 Realm 名称也要匹配

4. **检查 Gateway 日志**
   ```bash
   docker-compose logs gateway
   ```
   查看是否有 JWT 验证错误信息，如：
   - `Failed to validate the token`
   - `Unable to retrieve JWK Set from URI`

5. **网络连接**
   - 确保 Gateway 容器能访问 Keycloak（在同一个 Docker 网络中）
   - 检查 `docker-compose.yml` 中的 `depends_on` 配置

### Q: Gateway 启动失败？

**A:** 检查：
1. **端口冲突**：确保 8080 端口未被占用
2. **构建失败**：查看构建日志
   ```bash
   docker-compose build gateway
   ```
3. **依赖服务未就绪**：确保 Keycloak 和 Redis 已启动并健康

### Q: 前端跨域问题？

**A:** 在 Keycloak Client 配置中：
- 设置 `Web origins: *`（开发环境）
- 设置 `Valid redirect URIs: http://localhost:3000/*`

### Q: Token 过期时间太短？

**A:** 在 Keycloak Realm Settings → Tokens 中可以调整：
- `Access Token Lifespan`: Access Token 有效期
- `SSO Session Idle`: SSO 会话空闲时间

---

## 11. Docker Compose 使用说明

### 重新构建 Gateway

如果修改了 Gateway 代码，需要重新构建：

```bash
# 重新构建并启动
docker-compose up -d --build gateway

# 或者强制重新构建（不使用缓存）
docker-compose build --no-cache gateway
docker-compose up -d gateway
```

### 停止所有服务

```bash
# 停止但不删除容器
docker-compose stop

# 停止并删除容器（数据卷保留）
docker-compose down

# 停止并删除容器和数据卷（⚠️ 会删除所有数据）
docker-compose down -v
```

### 重启服务

```bash
# 重启所有服务
docker-compose restart

# 重启特定服务
docker-compose restart gateway
```

### 查看资源使用

```bash
# 查看容器资源使用情况
docker stats
```

---

## 12. 环境配置说明

### 本地开发环境

如果要在本地运行 Gateway（不通过 Docker），需要：
1. 确保 Redis 和 Keycloak 已通过 Docker 启动
2. 修改 `application.yml` 中的配置：
   - `issuer-uri: http://localhost:8180/auth/realms/gamehub`
   - Redis: `host: localhost`

### 容器环境

Gateway 在容器中运行时，会自动使用 `application-docker.yml` 配置：
- Keycloak 地址：`http://keycloak:8180`（使用服务名）
- Redis 地址：`redis`（使用服务名）

---

## 13. 生产环境建议

1. **修改默认密码**：更改 Keycloak Admin 密码
2. **使用 HTTPS**：配置 SSL 证书
3. **限制 Web origins**：不要使用 `*`，指定具体域名
4. **配置数据库持久化**：确保 PostgreSQL 数据卷持久化
5. **配置备份策略**：定期备份 Keycloak 数据
6. **监控与日志**：接入日志和监控系统（Prometheus、Grafana）
7. **资源限制**：在 `docker-compose.yml` 中为各服务配置资源限制
8. **健康检查**：确保所有服务都配置了健康检查
9. **密钥管理**：使用 Docker Secrets 或 K8s Secrets 管理敏感信息

---

---

## 14. 快速开始检查清单

- [ ] 执行 `docker-compose up -d` 启动所有服务
- [ ] 等待 Keycloak 启动完成（约 1-2 分钟）
- [ ] 访问 http://localhost:8180 登录 Keycloak Admin Console
- [ ] 创建 Realm: `gamehub`
- [ ] 创建 Client: `game-platform`，启用 `Standard flow` 和 `Direct access grants`
- [ ] 创建 Roles: `PLAYER`, `GUEST`, `ADMIN`
- [ ] 创建测试用户并分配 `PLAYER` 角色
- [ ] 获取 Token 测试登录功能
- [ ] 通过 Gateway 验证 JWT 验证功能
- [ ] 检查 Gateway 日志确认用户信息注入成功

---

更多信息请参考：
- [Keycloak 官方文档](https://www.keycloak.org/documentation)
- [Spring Cloud Gateway 文档](https://spring.io/projects/spring-cloud-gateway)

