# 🎮 Game Hub - 实时对战游戏平台 （V1.0）

> 基于 Java 微服务架构的实时对战小游戏平台，支持多人在线游戏、实时通信、社交功能，当前实现五子棋游戏，可扩展支持多品类游戏。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.13-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.6-blue.svg)](https://spring.io/projects/spring-cloud)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 目录

- [项目简介](#-项目简介)
- [核心特性](#-核心特性)
- [技术栈](#-技术栈)
- [系统架构](#-系统架构)
- [项目结构](#-项目结构)
- [快速开始](#-快速开始)
- [服务说明](#-服务说明)
- [开发指南](#-开发指南)
- [部署说明](#-部署说明)
- [文档](#-文档)
- [贡献指南](#-贡献指南)
- [许可证](#-许可证)

---

## 🎯 项目简介

**Game Hub** 是一个基于微服务架构的实时对战游戏平台，旨在提供一站式的游戏大厅、匹配、聊天室、个人成长体系等功能。平台采用云原生设计，支持统一身份鉴权、实时通信、多游戏接入。

### 产品定位

- **玩家**：一站式大厅、匹配、聊天室、个人成长体系、跨端无缝体验，能在多种小游戏间自由切换
- **运营**：精细化用户管理、权限配置、在线会话监控、活动与内容投放，支持多游戏联动运营
- **技术**：云原生微服务、统一身份鉴权、可观测性、快速接入"任意类型小游戏引擎"

### 当前状态

✅ **已完成**：
- 五子棋游戏（PVP/PVE 模式）
- 用户认证与授权（Keycloak OAuth2/OIDC）
- 实时通信（WebSocket + STOMP）
- 社交功能（好友系统、私聊、房间聊天）
- 会话管理（单设备登录、踢线功能）

🚧 **规划中**：
- 匹配服务
- 战绩持久化与复盘
- 后台管理界面
- 可观测性（Prometheus/Grafana）
- Kubernetes 部署

---

## ✨ 核心特性

### 🎮 游戏功能

- **五子棋游戏**：支持 PVP（玩家对战）和 PVE（人机对战）模式
- **房间系统**：创建房间、加入房间、房间列表、离开房间
- **实时对局**：基于 WebSocket 的实时棋盘状态同步
- **倒计时系统**：回合制倒计时，超时自动处理
- **AI 引擎**：支持人机对战，AI 难度可配置

### 🔐 认证与授权

- **Keycloak 集成**：OAuth2/OIDC 标准协议，支持单点登录
- **JWT 验证**：Gateway 统一入口鉴权，Token 自动刷新
- **会话管理**：登录会话注册、WebSocket 会话映射、多端互踢
- **Token 黑名单**：支持 Token 撤销，登出时自动加入黑名单

### 💬 社交功能

- **好友系统**：好友申请、同意/拒绝、好友列表管理
- **私聊消息**：点对点通信，支持已读未读状态、未读计数
- **房间聊天**：游戏房间内实时交流，支持历史消息加载
- **系统通知**：WebSocket 实时推送通知（好友申请、系统通知等）

### 🏗️ 架构特性

- **微服务架构**：服务边界清晰，支持独立部署和扩展
- **实时通信**：双 WebSocket 连接架构（游戏逻辑 + 聊天功能独立）
- **事件驱动**：Kafka 事件总线，支持解耦扩展
- **数据持久化**：PostgreSQL 持久化 + Redis 缓存/状态

---

## 🛠️ 技术栈

### 后端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| **Java** | 21 | 编程语言 |
| **Spring Boot** | 3.3.13 | 应用框架 |
| **Spring Cloud** | 2023.0.6 | 微服务框架 |
| **Spring Security** | - | 安全框架 |
| **Keycloak** | 25.0.1 | 身份认证服务 |
| **PostgreSQL** | 17.6 | 关系型数据库 |
| **Redis** | 7-alpine | 缓存/状态存储 |
| **Kafka** | - | 消息队列 |
| **MinIO** | - | 对象存储 |
| **Maven** | - | 构建工具 |

### 前端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| **React** | 19.2.0 | UI 框架 |
| **Vite** | 7.2.4 | 构建工具 |
| **React Router** | 7.9.6 | 路由管理 |
| **SockJS** | 1.6.1 | WebSocket 传输层 |
| **STOMP.js** | 7.2.1 | STOMP 协议客户端 |

### 基础设施

- **Docker** & **Docker Compose**：容器化部署
- **Spring Cloud Gateway**：API 网关
- **OpenFeign**：服务间调用
- **Spring WebSocket**：实时通信

---

## 🏛️ 系统架构

### 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        前端层                                │
│  React SPA (game-hub-web)                                    │
│  - 游戏大厅、个人中心、游戏房间                               │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       │ HTTPS / WebSocket
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                      接入层                                  │
│  Spring Cloud Gateway                                        │
│  - JWT 验证、路由、跨域、限流                                │
│  - WebSocket 转发、Token 透传                                │
└──────────────────────┬──────────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ game-service │ │chat-service  │ │system-service│
│ 游戏服务     │ │ 聊天服务     │ │ 用户服务     │
│ - 五子棋     │ │ - 大厅聊天   │ │ - 用户管理   │
│ - 房间管理   │ │ - 私聊       │ │ - 好友系统   │
│ - AI 引擎    │ │ - 系统通知   │ │ - 权限管理   │
└──────────────┘ └──────────────┘ └──────────────┘
        │              │              │
        └──────────────┼──────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Redis      │ │  PostgreSQL  │ │    Kafka     │
│ 缓存/状态    │ │ 数据持久化   │ │ 事件总线     │
└──────────────┘ └──────────────┘ └──────────────┘
```

### 服务说明

| 服务 | 端口 | 职责 |
|------|------|------|
| **gateway** | 8080 | API 网关，统一入口，JWT 验证，路由转发 |
| **game-service** | 8081 | 游戏服务，五子棋逻辑，房间管理，WebSocket |
| **system-service** | 8082 | 用户服务，用户管理，RBAC 权限，好友系统 |
| **chat-service** | 8083 | 聊天服务，大厅/房间/私聊，系统通知 |

### 数据存储

- **PostgreSQL**：用户数据、权限数据、聊天历史、好友关系
- **Redis**：房间状态、游戏状态、会话管理、倒计时状态、消息缓存
- **Kafka**：会话事件发布/订阅
- **MinIO**：头像、游戏回放、活动素材文件存储

---

## 📁 项目结构

```
game-hub-parent/
├── apps/                          # 应用模块
│   ├── gateway/                   # API 网关服务
│   │   ├── src/main/java/        # 网关核心代码
│   │   └── AUTH_GUIDE.md         # 认证指南
│   ├── game-service/              # 游戏服务
│   │   ├── src/main/java/        # 游戏核心代码
│   │   │   └── games/gomoku/     # 五子棋游戏实现
│   │   └── src/main/resources/   # 配置文件
│   ├── system-service/            # 用户服务
│   │   ├── src/main/java/        # 用户管理代码
│   │   └── src/main/resources/   # 配置文件
│   └── chat-service/              # 聊天服务
│       ├── src/main/java/        # 聊天核心代码
│       └── src/main/resources/   # 配置文件
├── libs/                          # 公共库
│   ├── session-common/            # 会话管理公共库
│   └── session-kafka-notifier/    # 会话事件通知库
├── docs/                          # 项目文档
│   ├── 项目总体蓝图与架构层级图.md
│   ├── 完整数据库设计-V1.0.md
│   ├── game-service/              # 游戏服务文档
│   ├── chat-service/              # 聊天服务文档
│   ├── system-service/            # 用户服务文档
│   └── Keycloak配置指南.md
├── docker-compose.yml             # Docker Compose 配置
├── pom.xml                        # Maven 父 POM
└── README.md                      # 项目说明文档
```

---

## 🚀 快速开始

### 前置要求

- **Java** 21+
- **Maven** 3.8+
- **Docker** & **Docker Compose**
- **Git**

### 1. 克隆项目

```bash
git clone https://github.com/your-username/game-hub-parent.git
cd game-hub-parent
```

### 2. 启动基础设施

使用 Docker Compose 启动 PostgreSQL、Redis、Kafka、Keycloak、MinIO：

```bash
docker-compose up -d postgres redis kafka keycloak minio
```

等待所有服务启动完成（约 1-2 分钟）。

### 3. 配置 Keycloak

1. 访问 Keycloak 管理控制台：http://localhost:8180
2. 使用管理员账号登录：`admin` / `admin`
3. 创建 Realm：`gamehub`
4. 创建 Client：`gamehub-client`
5. 配置用户和角色

详细配置请参考：[Keycloak配置指南.md](docs/Keycloak配置指南.md)

### 4. 初始化数据库

执行数据库初始化脚本（如果需要）：

```bash
# 连接 PostgreSQL
docker exec -it pgsql psql -U postgres -d mydb

# 执行初始化脚本（参考 docs/完整数据库设计-V1.0.md）
```

### 5. 编译项目

```bash
mvn clean install -DskipTests
```

### 6. 启动服务

#### 方式一：使用 Docker Compose（推荐）

```bash
# 启动所有服务（包括基础设施和应用服务）
docker-compose up -d
```

#### 方式二：本地启动

```bash
# 启动 Gateway
cd apps/gateway
mvn spring-boot:run

# 启动 Game Service（新终端）
cd apps/game-service
mvn spring-boot:run

# 启动 System Service（新终端）
cd apps/system-service
mvn spring-boot:run

# 启动 Chat Service（新终端）
cd apps/chat-service
mvn spring-boot:run
```

### 7. 验证服务

- **Gateway**：http://localhost:8080
- **Keycloak**：http://localhost:8180
- **pgAdmin**：http://localhost:5050
- **MinIO Console**：http://localhost:9001

### 8. 启动前端

前端项目位于独立的仓库 `game-hub-web`，请参考前端项目的 README 启动。

---

## 🔧 服务说明

### Gateway（API 网关）

**端口**：8080

**功能**：
- JWT 验证和 Token 刷新
- 路由转发到后端服务
- WebSocket 连接转发
- 跨域配置
- Token 黑名单管理
- 会话管理和踢线功能

**配置**：`apps/gateway/src/main/resources/application.yml`

### Game Service（游戏服务）

**端口**：8081

**功能**：
- 五子棋游戏逻辑（PVP/PVE）
- 房间管理（创建、加入、离开）
- WebSocket 实时对局通信
- 倒计时系统
- AI 引擎
- 游戏状态管理

**API 文档**：参考 [game-service技术说明文档.md](docs/game-service/game-service技术说明文档.md)

### System Service（用户服务）

**端口**：8082

**功能**：
- 用户管理（CRUD）
- RBAC 权限系统
- 好友系统（申请、同意、列表）
- 通知系统
- 文件存储（头像上传）
- Keycloak 事件监听

**API 文档**：参考 [system-service技术文档.md](docs/system-service/system-service技术文档.md)

### Chat Service（聊天服务）

**端口**：8083

**功能**：
- 大厅聊天
- 房间聊天
- 私聊消息
- 系统通知推送
- 消息持久化（Redis + PostgreSQL）

**API 文档**：参考 [chat-service技术文档.md](docs/chat-service/chat-service技术文档.md)

---

## 💻 开发指南

### 开发环境配置

1. **IDE 推荐**：IntelliJ IDEA 或 Eclipse
2. **插件**：Lombok、Spring Boot DevTools
3. **代码规范**：遵循 Google Java Style Guide

### 本地开发

1. **启动基础设施**：
   ```bash
   docker-compose up -d postgres redis kafka keycloak minio
   ```

2. **配置本地环境**：
   - 修改各服务的 `application.yml`，使用本地配置
   - 确保 Keycloak 已配置完成

3. **运行服务**：
   - 使用 IDE 直接运行各服务的 `Application` 类
   - 或使用 Maven：`mvn spring-boot:run`

### 代码结构

项目采用 **DDD（领域驱动设计）** 思想，代码分层：

```
service/
├── domain/              # 领域层（实体、值对象、领域服务）
├── application/         # 应用层（用例编排）
├── infrastructure/      # 基础设施层（数据访问、外部服务）
└── interface/           # 接口层（HTTP、WebSocket）
    ├── http/            # REST API
    └── ws/              # WebSocket
```

### 添加新游戏

1. 在 `game-service/src/main/java/com/gamehub/gameservice/games/` 下创建新游戏目录
2. 实现游戏规则、AI、状态管理等模块
3. 参考 `gomoku/` 目录的实现

详细说明请参考：[项目总体蓝图与架构层级图.md](docs/项目总体蓝图与架构层级图.md)

### 测试

```bash
# 运行所有测试
mvn test

# 运行特定服务的测试
cd apps/game-service
mvn test
```

---

## 🐳 部署说明

### Docker Compose 部署

项目提供了完整的 Docker Compose 配置，支持一键启动所有服务：

```bash
# 启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f gateway
docker-compose logs -f game-service

# 停止所有服务
docker-compose down
```

### 生产环境部署

生产环境建议使用 **Kubernetes** 部署，当前项目已提供 K8s 配置文件（规划中）。

**注意事项**：
- 修改默认密码（PostgreSQL、Redis、Keycloak）
- 配置 HTTPS
- 设置资源限制
- 配置健康检查和自动重启
- 接入监控和日志系统

---

## 📚 文档

### 核心文档

- [项目总体蓝图与架构层级图.md](docs/项目总体蓝图与架构层级图.md) - 项目整体架构和规划
- [完整数据库设计-V1.0.md](docs/完整数据库设计-V1.0.md) - 数据库表结构设计
- [Keycloak配置指南.md](docs/Keycloak配置指南.md) - Keycloak 配置说明

### 服务文档

- [game-service技术说明文档.md](docs/game-service/game-service技术说明文档.md) - 游戏服务详细说明
- [chat-service技术文档.md](docs/chat-service/chat-service技术文档.md) - 聊天服务详细说明
- [system-service技术文档.md](docs/system-service/system-service技术文档.md) - 用户服务详细说明
- [gateway/AUTH_GUIDE.md](apps/gateway/AUTH_GUIDE.md) - 认证授权流程说明

### 其他文档

- [单设备登录系统完整实现详解.md](docs/单设备登录系统完整实现详解.md) - 单设备登录实现
- [MinIO存储结构.md](docs/MinIO存储结构.md) - 文件存储说明
- [项目风险清单与示例.md](docs/项目风险清单与示例.md) - 项目风险分析

---

## 🤝 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. **Fork** 本仓库
2. 创建特性分支：`git checkout -b feature/AmazingFeature`
3. 提交更改：`git commit -m 'Add some AmazingFeature'`
4. 推送到分支：`git push origin feature/AmazingFeature`
5. 提交 **Pull Request**

### 代码规范

- 遵循 Google Java Style Guide
- 提交前运行 `mvn clean install` 确保编译通过
- 添加必要的单元测试
- 更新相关文档

### 问题反馈

如发现问题，请在 [Issues](https://github.com/your-username/game-hub-parent/issues) 中提交。

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

---

## 👥 作者

- **lhlh1992** - *初始开发* - [GitHub](https://github.com/lhlh1992)

---

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot) - 应用框架
- [Keycloak](https://www.keycloak.org/) - 身份认证服务
- [React](https://react.dev/) - 前端框架

---

## 📞 联系方式

- **Issues**：[GitHub Issues](https://github.com/your-username/game-hub-parent/issues)
- **Email**：your-email@example.com

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐ Star！**

Made with ❤️ by Game Hub Team

</div>

