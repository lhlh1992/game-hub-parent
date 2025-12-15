# 🎮 Game Hub - 实时对战游戏平台

> 基于 Java 微服务架构的生产级实时对战游戏平台，具备 WebSocket 实时通信、会话治理、房间状态管理和跨服务协作能力。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.13-brightgreen.svg)](https://spring.io/projects/spring-boot) [![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.6-blue.svg)](https://spring.io/projects/spring-cloud) [![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 目录
- [项目简介](#项目简介)
- [核心工程能力](#核心工程能力)
- [产品概览](#产品概览)
- [系统架构](#系统架构)
- [🔧 系统能力](#-系统能力)
- [🛠️ 技术栈](#-技术栈)
- [🚀 快速开始](#-快速开始)
- [📚 文档](#-文档)

---

## 项目简介
- 实时多人对战平台（当前落地五子棋 PVP/PVE），验证实时通信、会话一致性、并发隔离、状态恢复等工程能力。
- 可运行的微服务工程：Spring Cloud + WebSocket + Kafka + Redis + PostgreSQL + Keycloak，强调运行可靠性与扩展性。

---

## 核心工程能力
- 双通道实时通信：游戏/聊天 WebSocket 分离，降低串扰与背压。
- 会话治理与单设备登录：Keycloak + JWT + 黑名单，多端互踢、会话映射。
- 对局重入/状态恢复：房间/对局状态落 Redis，断线重连、服务重启可恢复。
- 房间级并发隔离：单房间单执行上下文，保障指令有序，避免跨房竞争。
- 事件驱动解耦：Kafka 传播会话/通知/房间事件，服务松耦合、易扩展。
- 五子棋引擎与裁剪：Threat-First + α-β 剪枝，回合计时、非法步校验。
- 通知体系：系统/好友申请通知持久化 + WebSocket 推送，UUID 去重与标记。
- 网关治理：统一路由、跨域、WebSocket 透传、限流与鉴权。

---

## 🎯 产品概览

工程视角的能力验证点：
- 五子棋对战：PVP/PVE，对局计时，非法步校验。
- 房间管理：创建/加入/列表，房间隔离与并发有序。
- 断线重连：对局状态、倒计时恢复。
- 好友与通知：好友申请/结果，系统通知推送与未读管理。
- 聊天：大厅/房间聊天，消息持久化与已读状态。
- 个人中心：用户资料、战绩统计、基础设置。

> 完整截图与演示说明见：[演示与截图指南](docs/demo-guide.md)（集中展示所有页面截图）。

---

## 🏛️ 系统架构

*整体微服务架构与实时通信关系图*

系统采用微服务架构，通过 Spring Cloud Gateway 作为统一入口，实现 JWT 统一鉴权、路由转发和 WebSocket 连接管理。核心服务包括游戏服务、聊天服务和用户服务，通过 Kafka 事件总线实现服务间解耦通信，使用 Redis 管理房间状态和会话信息，PostgreSQL 持久化业务数据。

### 架构图

```mermaid
graph TB
    subgraph "前端层"
        Web[React SPA<br/>game-hub-web]
    end
    
    subgraph "接入层"
        Gateway[Spring Cloud Gateway<br/>:8080<br/>JWT验证/路由/WebSocket转发]
    end
    
    subgraph "业务服务层"
        GameService[Game Service<br/>:8081<br/>游戏逻辑/房间管理/WebSocket]
        ChatService[Chat Service<br/>:8083<br/>聊天/系统通知]
        SystemService[System Service<br/>:8082<br/>用户管理/RBAC/好友系统]
    end
    
    subgraph "数据存储层"
        Redis[(Redis<br/>房间状态/游戏状态<br/>会话管理/消息缓存)]
        PostgreSQL[(PostgreSQL<br/>用户数据/权限数据<br/>聊天历史/好友关系)]
        Kafka[Kafka<br/>事件总线<br/>会话事件发布/订阅]
        MinIO[MinIO<br/>对象存储<br/>头像/回放/素材]
    end
    
    Web -->|HTTPS/WebSocket| Gateway
    Gateway -->|路由转发| GameService
    Gateway -->|路由转发| ChatService
    Gateway -->|路由转发| SystemService
    
    GameService <-->|状态存储/查询| Redis
    GameService <-->|事件发布/订阅| Kafka
    GameService -->|文件存储| MinIO
    
    ChatService <-->|消息缓存| Redis
    ChatService <-->|消息持久化| PostgreSQL
    ChatService <-->|事件发布/订阅| Kafka
    
    SystemService <-->|数据持久化| PostgreSQL
    SystemService <-->|会话管理| Redis
    SystemService <-->|事件发布/订阅| Kafka
    SystemService -->|文件存储| MinIO
    
    style Gateway fill:#e1f5ff
    style GameService fill:#fff4e1
    style ChatService fill:#e8f5e9
    style SystemService fill:#f3e5f5
    style Redis fill:#ffebee
    style PostgreSQL fill:#e3f2fd
    style Kafka fill:#fff9c4
    style MinIO fill:#e0f2f1
```

**架构特点**：
- **统一网关**：Gateway 层统一处理认证、路由和 WebSocket 转发
- **服务解耦**：通过 Kafka 事件总线实现异步通信，降低服务间耦合
- **状态管理**：Redis 存储房间和游戏状态，支持快速查询和恢复
- **实时通信**：双 WebSocket 连接架构，游戏和聊天功能独立管理

### 架构组件说明

| 服务 | 端口 | 职责 |
|------|------|------|
| **gateway** | 8080 | API 网关，统一入口，JWT 验证，路由转发，WebSocket 转发 |
| **game-service** | 8081 | 游戏服务，五子棋逻辑，房间管理，WebSocket 实时对局 |
| **system-service** | 8082 | 用户服务，用户管理，RBAC 权限，好友系统 |
| **chat-service** | 8083 | 聊天服务，大厅/房间/私聊，系统通知推送 |

### 数据存储

- **PostgreSQL**：用户数据、权限数据、聊天历史、好友关系
- **Redis**：房间状态、游戏状态、会话管理、倒计时状态、消息缓存
- **Kafka**：会话事件发布/订阅，跨服务事件驱动
- **MinIO**：头像、游戏回放、活动素材文件存储

---

## 🔧 系统能力

### WebSocket 实时通信

系统采用双 WebSocket 连接架构，游戏逻辑和聊天功能独立管理，降低耦合度。基于 STOMP 协议实现消息路由，支持点对点和广播通信。Gateway 层统一管理 WebSocket 连接，实现 Token 透传和连接状态监控。

- **双连接架构**：游戏 WebSocket 和聊天 WebSocket 独立管理
- **STOMP 协议**：标准化消息格式，支持订阅/发布模式
- **连接管理**：Gateway 层统一管理连接生命周期
- **消息路由**：支持点对点、房间广播、系统广播

### 会话治理

实现单设备登录机制，支持会话注册、WebSocket 会话映射和多端互踢功能。通过 Kafka 事件总线实现跨服务会话状态同步，Gateway 维护 Token 黑名单，支持 Token 撤销和登出时自动加入黑名单。

- **单设备登录**：同一用户仅允许一个活跃会话
- **会话映射**：HTTP 会话与 WebSocket 会话关联管理
- **多端互踢**：新设备登录自动踢出旧设备
- **Token 黑名单**：支持 Token 撤销，登出时自动加入黑名单

### 房间状态与对局重入

系统实现完整的对局状态持久化与恢复机制，确保客户端断线重连和服务端重启后均能完整恢复对局状态。

**状态持久化**：
- 房间状态和游戏状态存储在 Redis，包含完整的棋盘信息、玩家信息、回合状态和倒计时状态
- 游戏状态变更实时同步到 Redis，确保数据一致性
- 支持状态快照和增量更新两种模式

**客户端重入恢复**：
- 前端用户离开对局后重新进入，系统自动从 Redis 恢复完整的对局状态
- 客户端重连后通过 WebSocket 订阅房间状态，服务端推送当前对局快照
- 支持断线期间的落子历史回放，确保用户感知连续性

**服务端重入恢复**：
- 后端服务重启后，从 Redis 恢复所有活跃房间的游戏状态
- 服务启动时自动加载房间列表和对应游戏状态，重建内存中的游戏实例
- 通过事件机制通知其他服务房间状态已恢复，确保跨服务状态一致性

**技术实现**：
- 基于 Redis 的分布式状态存储，支持高可用和持久化
- 状态变更通过 Kafka 事件总线实现跨服务通知
- 倒计时系统基于 Redis 的分布式锁和过期机制

### 跨服务协作

通过 Spring Cloud Gateway 实现统一入口和路由转发，服务间通过 OpenFeign 进行同步调用，通过 Kafka 进行异步事件通信。Gateway 负责 JWT 验证和 Token 刷新，各业务服务专注于业务逻辑实现。

- **Gateway 统一入口**：JWT 验证、路由转发、跨域配置
- **服务间调用**：OpenFeign 同步调用，Kafka 异步事件
- **事件驱动**：Kafka 事件总线实现服务解耦
- **统一鉴权**：Gateway 层统一处理认证授权

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
| **Kafka** | 3.7.x | 消息队列 |
| **MinIO** | RELEASE.2023-04-28T18-11-17Z | 对象存储 |
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
- [演示与截图指南](docs/demo-guide.md) - 如何准备演示与放置截图

---

## 🤝 贡献指南

欢迎贡献代码。请遵循以下步骤：

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

### 

---

## 📄 许可证

无

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



- **Email**：602979266@qq.com

---

## 版权与使用说明

本项目源码仅用于学习交流，未经作者明确授权，不得用于商业用途、二次分发或作为他人项目成果使用。

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐ Star**

Made with ❤️ by Game Hub Team

</div>
