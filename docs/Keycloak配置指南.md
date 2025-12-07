# Keycloak 配置指南

## 1. 前置准备

### 1.1 环境要求

- ✅ Docker Desktop (Windows/Mac) 或 Docker Engine (Linux)
- ✅ Docker Compose v2.0+
- ✅ 已启动 PostgreSQL 和 Redis 服务

### 1.2 检查现有服务

```bash
# 进入项目目录
cd d:\workspace\java\game-hub-parent

# 检查现有服务状态
docker-compose ps

# 确保 PostgreSQL 和 Redis 正在运行
# 如果没有运行，先启动：
docker-compose up -d postgres redis
```

### 1.3 创建 Keycloak 数据库

Keycloak 需要使用 PostgreSQL 中的 `keycloak` 数据库，需要手动创建：

**方式1：使用 pgAdmin（推荐）**

1. 访问 http://localhost:5050 登录 pgAdmin
2. 连接到 PostgreSQL 服务器
3. 右键点击 `Databases` → `Create` → `Database`
4. 填写：
   - **Database name**: `keycloak`
   - **Owner**: `postgres`
5. 点击 `Save`

**方式2：使用 psql 命令行**

```bash
# 进入 PostgreSQL 容器
docker exec -it pgsql psql -U postgres

# 创建数据库
CREATE DATABASE keycloak;

# 退出
\q
```

**方式3：使用 SQL 脚本**

在 `D:/DockerData/pgsql/init/` 目录下创建文件 `01-create-keycloak-db.sql`：

```sql
CREATE DATABASE keycloak;
```

重启 PostgreSQL 服务即可自动执行。

---

## 2. 启动服务

### 2.1 构建 Gateway 镜像

```bash
# 在 game-hub-parent 目录下执行
docker-compose build gateway
```

**预期输出：**
- 下载 Maven 依赖
- 编译 Gateway 代码
- 构建 Docker 镜像

### 2.2 启动 Keycloak

```bash
# 启动 Keycloak 服务
docker-compose up -d keycloak

# 查看启动日志
docker-compose logs -f keycloak
```

**等待 Keycloak 启动完成**（约 1-2 分钟），直到看到：
```
Keycloak 25.0.1 started in XXms
```

### 2.3 启动 Gateway

```bash
# 启动 Gateway 服务（会自动等待 Keycloak 和 Redis 就绪）
docker-compose up -d gateway

# 查看启动日志
docker-compose logs -f gateway
```

### 2.4 验证服务状态

```bash
# 查看所有服务状态
docker-compose ps

# 应该看到所有服务都是 "Up" 状态
```

**预期输出：**
```
NAME                  STATUS
gamehub-gateway       Up (healthy)
gamehub-keycloak      Up (healthy)
pgsql                 Up
redis                 Up (healthy)
pgadmin               Up
```

### 2.5 访问服务

等待 Keycloak 启动完成（约 1-2 分钟），访问：
- **Keycloak Admin Console**: http://localhost:8180
  - 默认管理员账号: `admin` / `admin`
- **Gateway**: http://localhost:8080
  - 健康检查: http://localhost:8080/actuator/health

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

## 3. 创建 Realm

1. 登录 Keycloak Admin Console（http://localhost:8180）
2. 鼠标悬停在左上角 `Master` Realm，点击 `Create Realm`
3. 填写 Realm 名称：`my-realm`（与 Gateway 配置一致）
4. 点击 `Create`

---

## 4. 创建 Client（客户端）

### 3.1 创建 Gateway 使用的 Client（Confidential）

Gateway 作为 OAuth2 Client 需要 confidential client：

1. 在左侧菜单选择 `Clients`
2. 点击 `Create client`
3. 填写以下信息：
   - **Client type**: `OpenID Connect`
   - **Client ID**: `game-hub`（与 Gateway 配置一致）
4. 点击 `Next`
5. **Capability config** 页面：
   - 勾选 `Client authentication`: `On`（Gateway 使用 confidential client）
   - 勾选 `Authorization`: `Off`（如果不需要细粒度权限控制）
   - 勾选 `Standard flow`: `On`（授权码流程）
   - 勾选 `Direct access grants**: `Off`（Gateway 不需要密码模式）
6. 点击 `Next`
7. **Login settings** 页面：
   - **Valid redirect URIs**: `http://localhost:8080/login/oauth2/code/keycloak`（Gateway 回调地址）
   - **Web origins**: `http://localhost:8080`（Gateway 地址）
   - **Post logout redirect URIs**: `http://localhost:8080/*`
8. 点击 `Save`
9. 在 `Credentials` 标签页复制 `Client secret`，配置到 Gateway 的 `application.yml` 中

### 3.2 创建前端使用的 Client（Public，可选）

如果前端需要直接与 Keycloak 交互（当前项目通过 Gateway，此步骤可选）：

1. 创建新 Client，Client ID：`game-hub-web`
2. **Capability config**：
   - `Client authentication`: `Off`（公共客户端）
   - `Standard flow`: `On`
   - `Direct access grants`: `Off`
3. **Login settings**：
   - **Valid redirect URIs**: `http://localhost:5173/*`
   - **Web origins**: `http://localhost:5173`
   - **Post logout redirect URIs**: `http://localhost:5173/*`

**注意**：当前项目前端通过 Gateway 进行认证，不直接与 Keycloak 交互，因此主要使用 `game-hub`（confidential）客户端。

---

## 5. 创建 Roles（角色）

1. 在左侧菜单选择 `Realm roles`
2. 点击 `Create role`
3. 依次创建以下角色：
   - `PLAYER` - 普通玩家
   - `GUEST` - 游客
   - `ADMIN` - 管理员

---

## 6. 创建测试用户

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
3. 选择 `Filter by realm roles` → 选择 `PLAYER`（Realm 级别角色）
4. 点击 `Assign`

---

## 7. 配置游客 Token（可选）

如果需要生成游客 Token，有几种方案：

### 方案A：使用 Client Credentials 模式

1. 创建一个新的 Client，设置 `Client authentication: On`
2. 在 `Credentials` 标签获取 `Client secret`
3. 使用 Client Credentials 模式获取 Token，然后通过 Admin API 创建临时用户

### 方案B：在 Gateway 提供 `/auth/guest` 端点

在 Gateway 中创建一个端点，调用 Keycloak Admin API 生成临时用户和 Token。

---

## 8. 验证配置

### 获取 Access Token（通过 Gateway）

当前项目前端通过 Gateway 获取 Token，不直接调用 Keycloak：

```bash
# 方式1：通过 Gateway 的 /token 端点（推荐，自动处理刷新）
curl -X GET http://localhost:8080/token \
  -H "Cookie: JSESSIONID=<session-id>"

# 方式2：通过 Gateway 登录后获取（浏览器访问）
# 访问 http://localhost:8080/oauth2/authorization/keycloak
# 登录成功后，Gateway 会设置 session，前端可通过 /token 获取
```

**注意**：Gateway 使用 OAuth2 授权码流程，需要浏览器交互。直接测试可以使用以下方式：

```bash
# 测试 Gateway Token 端点（需要先登录获取 session）
curl -X GET http://localhost:8080/token \
  -b "JSESSIONID=<从浏览器复制的session-id>" \
  -c cookies.txt
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
# 使用获取到的 access_token 访问受保护接口
curl -X GET http://localhost:8080/game-service/me \
  -H "Authorization: Bearer <access_token>"

# 或访问用户信息接口
curl -X GET http://localhost:8080/system-service/api/users/me \
  -H "Authorization: Bearer <access_token>"
```

如果 Gateway 正确配置，应该会：
1. ✅ 验证 JWT Token（从 Keycloak 验证签名和有效期）
2. ✅ 提取用户信息并注入到请求头：`X-User-Id`, `X-Username`, `X-Roles`
3. ✅ 转发请求到下游服务（如 game-service、system-service）

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

## 9. Keycloak JWT Token 字段说明

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

## 10. 前端集成说明

当前项目前端**不直接与 Keycloak 交互**，而是通过 Gateway 进行认证。前端集成方式如下：

### 9.1 登录流程

```javascript
// 前端通过 Gateway 的 OAuth2 端点进行登录
// 文件：game-hub-web/src/services/auth/authService.js

// 1. 初始化并登录（如果未登录，自动跳转到 Gateway 登录页）
window.location.href = '/oauth2/authorization/keycloak'

// 2. Gateway 会重定向到 Keycloak 登录页
// 3. 用户登录成功后，Keycloak 重定向回 Gateway
// 4. Gateway 处理授权码，建立 session
// 5. 前端通过 /token 端点获取 access_token
```

### 9.2 获取 Token

```javascript
// 从 Gateway 获取 Token（自动处理刷新）
const response = await fetch('/token', {
  credentials: 'include'  // 携带 session cookie
})

const data = await response.json()
const accessToken = data.access_token

// 保存到 localStorage
localStorage.setItem('access_token', accessToken)
```

### 9.3 API 请求

```javascript
// 所有 API 请求自动携带 Token
fetch('/game-service/me', {
  headers: {
    'Authorization': `Bearer ${accessToken}`
  }
})
```

### 9.4 登出

```javascript
// 调用 Gateway 的登出端点
await fetch('/logout', {
  method: 'POST',
  credentials: 'include'
})

// 清理本地 Token
localStorage.removeItem('access_token')
```

**关键点**：
- 前端地址：`http://localhost:5173`（Vite 开发服务器）
- Gateway 地址：`http://localhost:8080`
- 前端不直接调用 Keycloak API
- 所有认证流程通过 Gateway 代理

---

## 11. 常见问题

### Q: Gateway 无法验证 JWT？

**A:** 检查：
1. **Keycloak 是否已启动并健康**
   ```bash
   docker-compose ps keycloak
   curl http://localhost:8180/health/ready
   ```

2. **Gateway 的 `issuer-uri` 是否正确**
   - 容器环境：通过 `KEYCLOAK_ISSUER_URI` 环境变量配置（注意：Keycloak 25+ 版本不需要 `/auth` 前缀）
   - 本地环境：`http://localhost:8180/realms/my-realm`（根据 application.yml）
   - **注意**：Keycloak 23+ 版本已移除 `/auth` 前缀，URL 格式为 `http://<host>:<port>/realms/<realm-name>`
   - 检查 Gateway 日志中的配置信息

3. **Realm 名称是否一致**
   - Keycloak 中创建的 Realm 名称必须是 `my-realm`
   - Gateway 配置中的 Realm 名称也要匹配（`application.yml` 中 `issuer-uri` 的 realm 部分）

4. **Client ID 和 Secret 是否匹配**
   - Gateway 配置的 `client-id` 必须是 `game-hub`
   - Gateway 配置的 `client-secret` 必须与 Keycloak Client 的 `Credentials` 中的 secret 一致

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

### Q: Gateway 无法连接到 Keycloak？

**A:** 检查：
1. ✅ Keycloak 是否已启动并健康
   ```bash
   docker-compose ps keycloak
   curl http://localhost:8180/health/ready
   ```
2. ✅ Gateway 配置中的 `KEYCLOAK_ISSUER_URI` 是否正确
3. ✅ 网络连接是否正常（同一 Docker 网络）
4. ✅ 查看 Gateway 日志：
   ```bash
   docker-compose logs gateway | grep -i "keycloak\|jwt\|token"
   ```

### Q: Keycloak 启动失败，提示数据库连接错误？

**A:** 检查：
1. ✅ PostgreSQL 是否正在运行
2. ✅ `keycloak` 数据库是否已创建（参考 1.3 节）
3. ✅ 数据库连接配置是否正确（用户名、密码、IP地址）

```bash
# 检查数据库是否存在
docker exec -it pgsql psql -U postgres -l | grep keycloak
```

### Q: Gateway 启动失败？

**A:** 检查：
1. **端口冲突**：确保 8080 端口未被占用
2. **构建失败**：查看构建日志
   ```bash
   docker-compose build gateway
   ```
3. **依赖服务未就绪**：确保 Keycloak 和 Redis 已启动并健康

### Q: 前端跨域问题？

**A:** 当前项目前端通过 Gateway 代理，不直接与 Keycloak 交互，因此：
- Gateway 的 `game-hub` Client 需要配置正确的 `Valid redirect URIs`：`http://localhost:8080/login/oauth2/code/keycloak`
- 如果前端需要直接访问 Keycloak（不推荐），需要创建 public client 并配置：
  - `Web origins: http://localhost:5173`
  - `Valid redirect URIs: http://localhost:5173/*`

### Q: Token 过期时间太短？

**A:** 在 Keycloak Realm Settings → Tokens 中可以调整：
- `Access Token Lifespan`: Access Token 有效期
- `SSO Session Idle`: SSO 会话空闲时间

---

## 12. Docker Compose 使用说明

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

## 13. 环境配置说明

### 本地开发环境

如果要在本地运行 Gateway（不通过 Docker），需要：
1. 确保 Redis 和 Keycloak 已通过 Docker 启动
2. 修改 `application.yml` 中的配置：
   - `issuer-uri: http://localhost:8180/realms/my-realm`
   - `client-id: game-hub`
   - `client-secret: <从 Keycloak 复制的 secret>`
   - Redis: `host: localhost`

### 容器环境

Gateway 在容器中运行时，通过环境变量配置（`docker-compose.yml`）：
- Keycloak 地址：`http://172.30.0.13:8180`（使用固定 IP）
- Keycloak Realm：通过 `KEYCLOAK_ISSUER_URI` 环境变量配置完整 URL
- **注意**：Keycloak 25+ 版本 URL 格式为 `http://<host>:<port>/realms/<realm-name>`（无 `/auth` 前缀）
- Redis 地址：`172.30.0.12`（使用固定 IP）

**配置示例**（docker-compose.yml）：
```yaml
environment:
  KEYCLOAK_ISSUER_URI: http://172.30.0.13:8180/realms/my-realm
```

---

## 14. 生产环境建议

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

## 15. 服务管理命令

### 启动服务

```bash
# 启动所有服务
docker-compose up -d

# 启动指定服务
docker-compose up -d keycloak gateway
```

### 停止服务

```bash
# 停止所有服务
docker-compose stop

# 停止指定服务
docker-compose stop gateway
```

### 查看日志

```bash
# 查看所有服务日志
docker-compose logs -f

# 查看指定服务日志
docker-compose logs -f gateway
docker-compose logs -f keycloak
```

### 重启服务

```bash
# 重启所有服务
docker-compose restart

# 重启特定服务
docker-compose restart gateway
```

### 删除服务

```bash
# 停止并删除容器（数据卷保留）
docker-compose down

# 停止并删除容器和数据卷（⚠️ 会删除所有数据）
docker-compose down -v
```

### 重置 Keycloak 配置

```bash
# 停止服务
docker-compose stop keycloak

# 删除 Keycloak 数据卷（⚠️ 会删除所有配置）
docker-compose down -v keycloak

# 重新创建数据库（参考 1.3 节）

# 重新启动
docker-compose up -d keycloak
```

---

## 16. 快速开始检查清单

- [ ] 执行 `docker-compose up -d` 启动所有服务
- [ ] 等待 Keycloak 启动完成（约 1-2 分钟）
- [ ] 访问 http://localhost:8180 登录 Keycloak Admin Console
- [ ] 创建 Realm: `my-realm`
- [ ] 创建 Client: `game-hub`（confidential），启用 `Client authentication` 和 `Standard flow`
- [ ] 在 Client 的 `Credentials` 标签复制 `Client secret`，配置到 Gateway 的 `application.yml`
- [ ] 配置 Client 的 `Valid redirect URIs`: `http://localhost:8080/login/oauth2/code/keycloak`
- [ ] 创建 Roles: `PLAYER`, `GUEST`, `ADMIN`
- [ ] 创建测试用户并分配 `PLAYER` 角色
- [ ] 访问 http://localhost:8080/oauth2/authorization/keycloak 测试登录
- [ ] 通过 Gateway 的 `/token` 端点获取 Token
- [ ] 使用 Token 访问 `/game-service/me` 验证 JWT 验证功能
- [ ] 检查 Gateway 日志确认用户信息注入成功

---

更多信息请参考：
- [Keycloak 官方文档](https://www.keycloak.org/documentation)
- [Spring Cloud Gateway 文档](https://spring.io/projects/spring-cloud-gateway)

