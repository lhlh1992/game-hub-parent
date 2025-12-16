# 游戏服务综合技术文档

**文档范围说明**：
- 本文档专注于 **game-service** 的业务逻辑和代码实现
- 本文档包含前端（game-hub-web）的游戏业务逻辑，但不涉及认证、鉴权、单点登录相关内容（详见单点登录文档）

## 目录
1. [服务概述](#服务概述)
2. [技术栈](#技术栈)
3. [系统架构](#系统架构)
4. [业务逻辑详解](#业务逻辑详解)
5. [代码架构分析](#代码架构分析)
6. [前后端交互流程](#前后端交互流程)
7. [数据模型与存储](#数据模型与存储)
8. [关键技术实现](#关键技术实现)
9. [代码细节分析](#代码细节分析)
10. [前端详细分析](#前端详细分析)
11. [后续改进计划](#后续改进计划)

---

## 服务概述

### 1.1 服务定位
**game-service** 是通用游戏服务，负责游戏规则、对局执行、房间管理等核心功能。

**当前实现**：五子棋游戏，支持：
- **PVP模式**：玩家对战
- **PVE模式**：人机对战
- **房间系统**：创建/加入/离开房间
- **实时对局**：WebSocket实时通信
- **倒计时系统**：回合制倒计时
- **系列赛**：多盘对局，比分统计

**可扩展性**：服务采用模块化设计，在`games/`目录下按游戏类型组织代码。未来可在`games/`下添加其他游戏（如中国跳棋、象棋等），每个游戏独立实现规则、AI、状态管理等模块。

**说明**：本文档主要描述game-service的通用架构和当前五子棋的实现，不涉及整个项目的其他服务。

### 1.2 相关服务
- **game-service**：游戏服务（游戏规则、对局执行、房间管理，当前实现五子棋，未来可扩展其他游戏）**← 本文档**
- **game-hub-web**：前端应用（React + Vite）
- **gateway**：API网关（路由、网关功能）
- **system-service**：用户服务（用户信息、用户资料）

---

## 技术栈

### 2.1 后端技术栈（game-service）
- **框架**：Spring Boot 3.x
- **语言**：Java 21
- **Web框架**：Spring Web + Spring WebSocket (STOMP)
- **安全**：Spring Security + OAuth2 Resource Server (JWT)
- **数据存储**：Redis（房间状态、游戏状态、座位绑定）
- **服务调用**：OpenFeign（调用 system-service）
- **负载均衡**：Spring Cloud LoadBalancer
- **会话管理**：session-common（WebSocket会话管理）
- **消息队列**：Kafka（会话失效通知）

### 2.2 前端技术栈（game-hub-web）
- **框架**：React 19.2
- **构建工具**：Vite 7.2
- **路由**：React Router 7.9
- **WebSocket**：SockJS 1.6.1 + @stomp/stompjs 7.2.1
- **状态管理**：React Context + Hooks
- **HTTP客户端**：原生fetch（封装为apiClient）
- **样式**：CSS模块化（global.css、header.css、game.css等）

---

## 系统架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        前端层                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  HomePage    │  │  LobbyPage   │  │ GameRoomPage │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│         │                  │                  │             │
│         └──────────────────┼──────────────────┘             │
│                            │                                │
└────────────────────────────┼────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │   Gateway      │
                    │  (路由/网关)    │
                    └────────┬───────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐
│ game-service  │  │ system-service  │  │   Kafka         │
│               │  │                 │  │  (会话通知)      │
│ - 房间管理     │  │ - 用户信息      │  └─────────────────┘
│ - 游戏逻辑     │  └─────────────────┘
│ - WebSocket   │
│ - Redis存储   │
└───────┬───────┘
        │
┌───────▼───────┐
│    Redis      │
│ - 房间元信息   │
│ - 游戏状态     │
│ - 座位绑定     │
│ - 倒计时锚点   │
└──────────────┘
```

### 3.2 后端分层架构

```
game-service/
├── GameServiceApplication.java                     # Spring Boot启动类（@SpringBootApplication、@EnableFeignClients）
├── games/              # 游戏模块（可扩展）
│   └── gomoku/         # 五子棋模块（当前实现）
│       ├── interfaces/ # 接口层
│       │   ├── http/   # REST API 控制器
│       │   │   ├── GomokuRestController.java      # 五子棋HTTP接口（创建房间、获取视图、加入/离开）
│       │   │   ├── RoomListController.java         # 房间列表查询（大厅用，分页）
│       │   │   ├── GomokuDebugController.java      # 五子棋调试接口（用于调试和排查）
│       │   │   └── dto/                            # HTTP DTO
│       │   │       ├── RoomListResponse.java       # 房间列表响应（items、nextCursor、hasMore）
│       │   │       └── RoomSummary.java            # 房间摘要（用于列表展示）
│       │   └── ws/     # WebSocket 控制器
│       │       ├── GomokuWsController.java        # WebSocket消息处理（落子、认输、准备、开始、重开、踢人）
│       │       ├── GomokuResumeController.java     # WebSocket恢复控制器（刷新重入，返回FullSync）
│       │       └── dto/                            # WebSocket DTO
│       │           ├── GomokuMessages.java         # WebSocket消息对象定义（PlaceCmd、BroadcastEvent等）
│       │           └── ResumeMessages.java         # 恢复握手与全量同步DTO（ResumeCmd、FullSync）
│       ├── service/    # 服务层
│       │   ├── GomokuService.java                  # 五子棋服务接口（定义核心业务方法）
│       │   └── impl/
│       │       └── GomokuServiceImpl.java          # 五子棋核心业务逻辑（房间管理、游戏状态、座位绑定等）
│       ├── domain/     # 领域层
│       │   ├── model/  # 领域模型
│       │   │   ├── Room.java                      # 房间实体（包含Series、座位绑定、seatKey映射）
│       │   │   ├── Game.java                      # 单盘游戏实体（gameId、index、state、pendingAi）
│       │   │   ├── GomokuState.java               # 游戏状态（棋盘、当前执子、胜负、上一手）
│       │   │   ├── Board.java                     # 棋盘（15x15网格，EMPTY/BLACK/WHITE）
│       │   │   ├── Move.java                      # 一步棋（坐标+棋子，record类型）
│       │   │   ├── GomokuSnapshot.java           # 房间只读快照（用于FullSync，包含完整房间状态）
│       │   │   ├── RoomView.java                  # 聚合后的房间视图（服务内部用，聚合Meta/Seats/Game/Anchor）
│       │   │   └── SeriesView.java                # 对局串信息视图（比分、第几盘、当前局ID）
│       │   ├── dto/    # 数据传输对象
│       │   │   ├── RoomMeta.java                  # 房间元信息（mode、rule、owner、series统计等）
│       │   │   ├── RoomMetaConverter.java         # 房间元信息转换器（转换逻辑）
│       │   │   ├── SeatsBinding.java               # 座位绑定信息（X/O座位、sessionId映射、ready状态）
│       │   │   ├── GameStateRecord.java            # 单盘棋局状态（Redis持久化，board、current、winner等）
│       │   │   ├── TurnAnchor.java                 # 回合计时锚点（side、deadline、turnSeq）
│       │   │   └── AiIntent.java                   # AI意图（用于节点重启恢复，scheduledAtMs）
│       │   ├── enums/  # 枚举类型
│       │   │   ├── Mode.java                       # 对战模式枚举（PVP、PVE）
│       │   │   ├── Rule.java                       # 规则枚举（STANDARD、RENJU）
│       │   │   └── RoomPhase.java                  # 房间阶段枚举（WAITING、PLAYING、ENDED）
│       │   ├── rule/   # 规则判定
│       │   │   ├── GomokuJudge.java               # 普通规则判定（五连、和棋、合法性）
│       │   │   ├── GomokuJudgeRenju.java          # 禁手规则判定（长连、四四、三三，仅黑方）
│       │   │   └── Outcome.java                    # 对局结果枚举（WIN、DRAW、ONGOING）
│       │   ├── ai/     # AI算法
│       │   │   ├── GomokuAI.java                  # AI主类（威胁优先+αβ搜索+禁手过滤）
│       │   │   └── Evaluator.java                  # 棋局评估函数（启发式评估，连子长度评分）
│       │   └── repository/  # 仓储接口
│       │       ├── RoomRepository.java            # 房间仓储接口（房间元信息、座位绑定、seatKey）
│       │       ├── GameStateRepository.java        # 游戏状态仓储接口（单盘棋局状态，支持CAS）
│       │       ├── TurnRepository.java            # 回合计时锚点仓储接口（side、deadline、turnSeq）
│       │       └── AiIntentRepository.java         # AI意图仓储接口（用于节点重启恢复）
│       ├── application/ # 应用编排层
│       │   └── TurnClockCoordinator.java          # 倒计时协调器（协调通用引擎与五子棋业务规则）
│       └── infrastructure/  # 基础设施层
│           └── redis/  # Redis实现
│               ├── RedisKeys.java                  # Redis Key统一管理（前缀、键名拼接）
│               └── repo/
│                   ├── RedisRoomRepository.java   	   # Redis房间仓储实现（RoomMeta、SeatsBinding、seatKey）
│                   ├── RedisGameStateRepository.java  # Redis游戏状态仓储实现（GameStateRecord，支持CAS）
│                   ├── RedisTurnRepository.java       # Redis回合计时锚点仓储实现（TurnAnchor）
│                   └── RedisAiIntentRepository.java   # Redis AI意图仓储实现（AiIntent，用于节点重启恢复）
│
├── platform/           # 平台层（通用基础设施）
│   ├── config/         # 平台配置类
│   │   └── SecurityConfig.java                    # Spring Security配置（JWT资源服务器、URL授权规则）
│   ├── ws/             # WebSocket基础设施
│   │   ├── WebSocketSessionManager.java           # WebSocket会话管理（注册、断开、踢旧连接，116行）
│   │   ├── WebSocketStompConfig.java             # WebSocket STOMP配置（消息转换、拦截器）
│   │   ├── WebSocketDisconnectHelper.java        # WebSocket断连工具类（统一断连方法）
│   │   ├── WebSocketAuthChannelInterceptor.java  # WebSocket认证拦截器（验证JWT token）
│   │   └── SessionInvalidatedListener.java       # 会话失效事件监听器（监听Kafka事件，断开连接）
│   ├── ongoing/        # 进行中游戏追踪
│   │   ├── OngoingGameTracker.java                # 进行中游戏追踪（记录/查询用户当前游戏，Redis实现）
│   │   └── OngoingGameInfo.java                   # 进行中游戏信息（gameType、roomId、title、updatedAt）
│   └── transport/      # 传输层（HTTP控制器）
│       ├── OngoingGameController.java            # 进行中游戏查询与清理（/api/ongoing-game）
│       ├── MeController.java                     # 当前用户信息查询（/me，从JWT提取）
│       └── Envelope.java                          # 响应包装类（统一响应格式）
│
├── application/        # 应用层（跨游戏通用服务）
│   └── user/           # 用户服务
│       ├── UserDirectoryService.java               # 用户目录服务（调用system-service获取用户信息，带熔断）
│       └── UserProfileView.java                    # 用户档案视图（用户信息DTO）
│
├── clock/              # 倒计时引擎（通用倒计时调度）
│   ├── ClockAutoConfig.java                        # 倒计时自动配置（Bean装配）
│   ├── ClockSchedulerConfig.java                   # 倒计时调度器配置（线程池配置）
│   └── scheduler/      # 倒计时调度器
│       ├── CountdownScheduler.java                 # 倒计时调度器接口（定义通用倒计时API）
│       └── CountdownSchedulerImpl.java             # 倒计时调度器实现（Redis持久化、TICK/超时回调）
│
├── engine/             # 游戏引擎核心（通用游戏框架）
│   └── core/           # 核心接口
│       ├── GameState.java                          # 游戏状态接口（copy方法，供AI搜索使用）
│       ├── AiAdvisor.java                          # AI建议接口（通用AI建议方法）
│       ├── Command.java                            # 游戏命令接口（通用命令模式）
│       └── EngineMode.java                         # 引擎模式枚举（通用模式定义）
│
├── common/             # 通用组件
│   └── WebExceptionAdvice.java                      # 全局异常处理器（统一异常响应格式）
│
└── infrastructure/     # 全局基础设施层
    ├── client/         # Feign客户端
    │   └── system/
    │       └── SystemUserClient.java              # System-service Feign客户端（获取用户信息）
    ├── redis/          # Redis基础设施
    │   ├── RedisConfig.java                        # Redis配置类（连接池、序列化等）
    │   └── RedisOps.java                           # Redis工具类（封装常用操作，String/Hash/Key/脚本）
    ├── scheduler/      # 调度器配置
    │   └── AiSchedulerConfig.java                  # AI调度器配置（PVE模式AI思考延迟专用线程池）
    └── config/         # 其他配置类
```

**架构说明**：
- `games/`目录：按游戏类型组织代码，每个游戏独立实现规则、AI、状态管理等模块
- `platform/`目录：提供通用的WebSocket、会话管理等基础设施，供所有游戏复用
- `infrastructure/`目录：全局基础设施（如Feign客户端、配置类）
- **扩展方式**：未来添加新游戏时，在`games/`下创建新的游戏目录（如`games/chess/`），按相同结构组织代码

### 3.3 前端架构

```
game-hub-web/src/
├── pages/              # 页面组件
│   ├── HomePage.jsx           # 首页（游戏大厅入口、进行中游戏提示）
│   ├── LobbyPage.jsx          # 游戏大厅（创建房间、加入房间、房间列表）
│   ├── GameRoomPage.jsx       # 游戏房间（五子棋对局界面）
│   ├── ProfilePage.jsx        # 个人中心（占位，待实现）
│   ├── SessionMonitorPage.jsx # 会话监控（调试用）
│   └── NotFoundPage.jsx       # 404页面
│
├── components/         # 组件
│   ├── layout/        # 布局组件
│   │   ├── AppLayout.jsx      # 应用布局（Header + Outlet + GlobalChat）
│   │   └── Header.jsx         # 顶部导航栏（用户信息）
│   ├── chat/          # 聊天组件
│   │   └── GlobalChat.jsx     # 全局聊天面板（可折叠）
│   └── common/         # 通用组件
│
├── hooks/             # 自定义Hooks
│   ├── useGomokuGame.js    # 五子棋游戏逻辑（核心Hook）
│   └── useOngoingGame.js   # 进行中游戏（从OngoingGameContext提取）
│
├── contexts/          # React Context
│   └── OngoingGameContext.jsx   # 进行中游戏上下文（查询、刷新、结束）
│
├── services/          # 服务层
│   ├── api/          # HTTP API
│   │   ├── apiClient.js         # HTTP客户端
│   │   ├── gameApi.js           # 游戏相关API
│   │   └── sessionMonitor.js    # 会话监控API（调试用）
│   ├── ws/           # WebSocket
│   │   └── gomokuSocket.js      # 五子棋WebSocket服务
│   └── index.js       # 服务导出
│
├── config/           # 配置
│   └── appConfig.js  # 应用配置（API地址、WebSocket地址等）
│
└── styles/           # 样式文件
    ├── global.css          # 全局样式
    ├── header.css          # 头部样式
    ├── home.css            # 首页样式
    ├── lobby.css           # 大厅样式
    ├── game.css            # 游戏房间样式
    ├── components.css      # 组件样式
    └── sessionMonitor.css  # 会话监控样式
```

---

## 业务逻辑详解

**说明**：本章节主要描述当前五子棋游戏的业务逻辑实现。game-service采用模块化设计，未来添加其他游戏时，每个游戏可独立实现各自的业务逻辑。

### 4.1 房间生命周期

#### 4.1.1 创建房间
**流程**：
1. 用户调用 `POST /api/gomoku/new`，传入 `mode`（PVP/PVE）、`aiPiece`（AI棋子颜色）、`rule`（规则）
2. 检查用户是否已有进行中的房间（`OngoingGameTracker`），如果有则返回409冲突
3. 生成 `roomId` 和 `gameId`（UUID）
4. 在Redis中创建：
   - `RoomMeta`：房间元信息（模式、规则、房主、创建时间、当前gameId、比分等）
   - `GameStateRecord`：首盘游戏状态（空棋盘，黑先）
   - `SeatsBinding`：座位绑定（房主默认绑定黑方，即seatXSessionId）
   - 房间索引（ZSET，用于大厅列表按创建时间倒序分页）
5. 记录到 `OngoingGameTracker`（供前端"继续游戏"使用）
6. 缓存房主用户信息到Redis（避免WS场景调用Feign）
7. 创建内存Room对象（用于快速访问）

**关键代码**：`GomokuServiceImpl.newRoom()`

#### 4.1.2 加入房间
**流程**：
1. 用户调用 `POST /api/gomoku/rooms/{roomId}/join`
2. 检查是否已在房间内（已绑定座位）
3. 检查是否已有其他进行中的房间
4. 调用 `resolveAndBindSide()` 分配座位并做并发占座保护：
   - **PVE模式**：玩家自动分配到与AI相反的一方
   - **PVP模式**：自动分配空位（先黑后白），不再支持意向座位
   - **并发占座**：对“房间+座位”加轻量级 SETNX 锁（TTL 2 分钟），写入成功后立即释放。锁只用于互斥，实际占座数据以 Redis `SeatsBinding` 为准。
5. 缓存用户信息
6. 记录到 `OngoingGameTracker`
7. 广播 `SNAPSHOT` 事件（通知房间内其他玩家）

**关键代码**：`GomokuServiceImpl.resolveAndBindSide()`

实现要点（并发占座）：
- Redis 键：`gomoku:room:{roomId}:seatLock:{X|O}`，用 SETNX+TTL 保护写入。
- 入口：`resolveAndBindSide` 先 `tryLockSeat`，写入 `SeatsBinding` 后立即 `releaseSeatLock`。
- 锁 TTL：2 分钟兜底，正常流程写完即释放，不阻塞后续进房。
- 位置：`game-service/src/main/java/com/gamehub/gameservice/games/gomoku/service/impl/GomokuServiceImpl.java`  
  `tryLockSeat/releaseSeatLock`：`.../infrastructure/redis/repo/RedisRoomRepository.java`  
  SETNX 封装：`.../infrastructure/redis/RedisOps.java`

#### 4.1.3 离开房间
**流程**：

1. 用户调用 `POST /api/gomoku/rooms/{roomId}/leave`
2. **PVE模式**：直接销毁房间
3. **PVP模式**：
   - 如果对手不存在：销毁房间
   - 如果对手存在：释放座位，转移房主（如果离开的是房主），清空对局状态，重置房间状态为 `WAITING`
4. 清理 `OngoingGameTracker`
5. 如果房间未销毁，广播 `SNAPSHOT` 事件

**关键代码**：`GomokuServiceImpl.leaveRoom()`

#### 4.1.4 房间生命周期存在的问题

当前房间生命周期实现虽然功能完整，但在设计规范上存在一些问题，后续需要优化：

1. **状态转换缺少统一管理**：`setRoomPhase()` 方法直接设置状态，没有状态转换规则验证，可能导致非法状态转换
2. **状态验证不完整**：只有部分操作（如 `startGame()`）有状态验证，很多地方直接调用 `setRoomPhase()` 没有验证当前状态
3. **ENDED 状态未使用**：虽然定义了 `ENDED` 状态，但实际代码中游戏结束后直接回到 `WAITING`，跳过了 `ENDED` 状态
4. **缺少并发控制**：状态转换没有分布式锁保护，多节点并发可能导致状态不一致
5. **职责过重**：所有状态管理逻辑都在 `GomokuServiceImpl` 中，没有独立的状态管理器

**改进方向**：引入状态机模式，统一管理状态转换规则和验证逻辑，添加并发控制，完善 `ENDED` 状态的使用。

### 4.2 游戏流程

#### 4.2.1 准备阶段（WAITING）
**状态**：`RoomPhase.WAITING`

- 玩家可以切换准备状态（`toggleReady()`）
- **PVE模式**：只需房主准备（AI默认已准备）
- **PVP模式**：需要所有玩家都准备（至少2名玩家）
- 房主可以点击"开始游戏"（`startGame()`）
- **WAITING状态下不允许落子**（前端和后端都会检查）

**关键代码**：
- `GomokuServiceImpl.toggleReady()`：切换准备状态
- `GomokuServiceImpl.startGame()`：开始游戏（检查房主权限、准备状态，切换phase: WAITING → PLAYING）

#### 4.2.2 游戏阶段（PLAYING）
**状态**：`RoomPhase.PLAYING`
- 玩家可以落子（`place()`）
- 系统自动切换回合（黑→白→黑...）
- 倒计时系统启动（`TurnClockCoordinator`）
- 胜负判定（`GomokuJudge`）
- **PVE模式**：AI自动落子（延迟1-1.5秒）

**关键代码**：
- `GomokuServiceImpl.place()`
- `GomokuWsController.place()`
- `TurnClockCoordinator.syncFromState()`

#### 4.2.3 终局处理
**触发条件**：
- 五连（`GomokuJudge.isWin()`）
- 棋盘已满（和棋）
- 认输（`resign()`）
- 超时（`TurnClockCoordinator.handleTimeout()`）

**处理流程**：
1. 更新比分（`incrSeriesOnFinish()`）
2. 清理回合计时锚点（`turnRepo.delete()`）
3. 重置准备状态（`resetAllReady()`）
4. 切换房间状态为 `WAITING`（下一局重新准备）
5. 广播 `STATE` 和 `SNAPSHOT` 事件

### 4.3 落子逻辑

#### 4.3.1 玩家落子
**流程**：
1. 前端发送 `place` 消息到 `/app/gomoku.place`
2. **PVE模式权限检查**：只有房主可以走棋
3. 身份绑定与座位分配（如果提供seatKey，则绑定座位）
4. 后端验证：
   - 房间状态必须是 `PLAYING`（WAITING状态下不允许落子）
   - 必须是当前执子方
   - 坐标合法且未被占用
   - 禁手检查（RENJU规则，仅黑方）
5. 计算CAS期望值（`expectedStep`：当前棋子数量，`expectedTurn`：当前执子方）
6. 执行落子（`GomokuState.apply()`）
7. 胜负判定（`GomokuJudge.outcomeAfterMove()`）
8. 如果未结束，切换回合；如果结束，更新比分、重置准备状态、切换房间状态为WAITING
9. **CAS保存**到Redis（`updateAtomically()`，防止并发）
10. 广播 `STATE` 和 `SNAPSHOT` 事件
11. 同步倒计时（`TurnClockCoordinator.syncFromState()`）
12. **PVE模式**：如果轮到AI，延迟1-1.5秒执行AI落子

**关键代码**：`GomokuWsController.place()`

#### 4.3.2 AI落子（PVE模式）
**流程**：
1. 玩家落子后，如果轮到AI，延迟1-1.5秒执行（随机延迟，模拟人类思考时间）
2. 调用 `GomokuAI.bestMove()` 获取建议
3. 执行AI落子（复用 `place()` 方法）
4. 保存状态并广播
5. 使用 `gameId` 防止AI跨盘操作（如果游戏重新开始，AI任务会被取消）

**关键代码**：
- `GomokuWsController.maybeScheduleAi()`：调度AI任务（延迟1-1.5秒）
- `GomokuWsController.runAiTurn()`：执行AI落子（验证gameId，防止跨盘操作）

### 4.4 倒计时系统

#### 4.4.1 架构
- **通用引擎**：`CountdownScheduler`（业务无关）
- **业务协调器**：`TurnClockCoordinator`（五子棋业务规则）

#### 4.4.2 工作流程
1. **启动**：应用启动时，`TurnClockCoordinator.onReady()` 注册TICK监听并恢复活跃任务
   - 注册TICK监听：每秒转发倒计时到房间（`/topic/room.{roomId}`）
   - 恢复活跃任务：恢复所有未过期的倒计时（已过期的触发一次超时）
2. **同步**：每次状态变更后，调用 `syncFromState()`：
   - 终局：停止计时
   - 房间状态不是PLAYING：停止计时
   - PVE且不计AI且轮到AI：停止计时（`aiTimed=false`时）
   - 其他：启动/续上倒计时（key=`gomoku:{roomId}`, owner=`X`/`O`, version=`gameId`）
3. **TICK**：后端每秒广播剩余时间到房间（`TICK`事件，包含left、side、deadlineEpochMs）
4. **超时**：到期后执行判负（`gomokuService.resign()`）并广播 `TIMEOUT`、`STATE`、`SNAPSHOT` 事件

**倒计时机制说明**：
- **后端**：使用 `CountdownScheduler` 每秒计算剩余时间并广播 `TICK` 事件到前端
- **前端**：接收 `TICK` 事件并显示倒计时。前端**不会自己计时**，只是显示后端发送的剩余秒数（`left`）
- **后端停止的情况**：如果后端停止发送 `TICK` 事件（如服务重启、网络中断），前端会停留在最后一次收到的倒计时值，不会继续递减。前端可以通过检测 `TICK` 事件中断来提示用户连接异常

**关键代码**：`TurnClockCoordinator`

---

## 代码架构分析

### 5.1 分层设计

#### 5.1.1 接口层（interfaces）
**职责**：
- 接收HTTP/WebSocket请求
- 参数验证
- 调用服务层
- 返回响应/广播消息

**关键类**：
- `GomokuRestController`：REST API（创建房间、加入房间、获取房间视图、离开房间）
- `RoomListController`：房间列表查询（大厅用，分页）
- `GomokuWsController`：WebSocket消息处理（落子、认输、准备、开始游戏、重开、踢人）
- `GomokuResumeController`：WebSocket恢复控制器（刷新重入，返回FullSync）
- `OngoingGameController`：进行中游戏查询与清理（`/api/ongoing-game`）
- `MeController`：当前用户信息查询（`/me`）

#### 5.1.2 服务层（service）
**职责**：
- 核心业务逻辑
- 房间生命周期管理
- 游戏状态管理
- 座位绑定管理
- 准备状态管理

**关键类**：
- `GomokuServiceImpl`：实现所有业务逻辑（1267行，核心类）

#### 5.1.3 领域层（domain）
**职责**：
- 领域模型（Room、Game、GomokuState、Board）
- 规则判定（GomokuJudge、GomokuJudgeRenju）
- AI算法（GomokuAI、Evaluator）
- 仓储接口（RoomRepository、GameStateRepository）

**关键类**：
- `Room`：房间实体（包含Series、座位绑定）
- `Game`：单盘游戏实体
- `GomokuState`：游戏状态（棋盘、当前执子、胜负）
- `Board`：棋盘（15x15）

#### 5.1.4 应用编排层（application）
**职责**：
- 协调通用组件与业务规则
- 倒计时业务协调

**关键类**：
- `TurnClockCoordinator`：倒计时协调器

#### 5.1.5 基础设施层（infrastructure）
**职责**：
- Redis实现（仓储实现）
- Feign客户端（调用system-service）
- 配置类

#### 5.1.6 平台层（platform）
**职责**：
- WebSocket基础设施（会话管理、断连处理）
- 进行中游戏追踪

**关键类**：
- `WebSocketSessionManager`：WebSocket会话管理


### 5.2 数据流向

```
HTTP/WebSocket请求
    ↓
Gateway (路由)
    ↓
Controller (接口层)
    ↓
Service (服务层)
    ↓
Domain (领域层) ← → Repository (仓储接口)
    ↓                           ↓
Redis (基础设施层)          Feign Client (调用system-service)
```

### 5.3 状态管理

#### 5.3.1 内存状态
- `ConcurrentHashMap<String, Room> rooms`：房间内存缓存（用于快速访问）
- 如果内存未命中，从Redis加载并缓存

#### 5.3.2 Redis状态
- **RoomMeta**：房间元信息（模式、规则、房主、创建时间、当前gameId、比分）
- **GameStateRecord**：游戏状态（棋盘字符串、当前执子、胜负、步数）
- **SeatsBinding**：座位绑定（seatXSessionId、seatOSessionId、seatBySession、readyByUserId）
- **TurnAnchor**：回合计时锚点（side、deadlineEpochMs、turnSeq）
- **UserProfile**：用户信息缓存（避免WS场景调用Feign）

---

## 前后端交互流程

**说明**：本章节描述当前五子棋游戏的前后端交互流程。不同游戏的交互流程可能不同，但都遵循相同的WebSocket和HTTP通信机制。

### 6.0 REST API端点列表

#### 6.0.1 五子棋相关接口（`/api/gomoku`）
| 方法 | 路径 | 说明 | 控制器 |
|------|------|------|--------|
| POST | `/new` | 创建房间 | `GomokuRestController` |
| GET | `/rooms/{roomId}/view` | 获取房间完整快照 | `GomokuRestController` |
| POST | `/rooms/{roomId}/join` | 加入房间 | `GomokuRestController` |
| POST | `/rooms/{roomId}/leave` | 离开房间 | `GomokuRestController` |
| GET | `/rooms?cursor=&limit=` | 获取房间列表（分页） | `RoomListController` |

#### 6.0.2 其他接口
| 方法 | 路径 | 说明 | 控制器 |
|------|------|------|--------|
| GET | `/api/ongoing-game` | 查询进行中的游戏 | `OngoingGameController` |
| POST | `/api/ongoing-game/end` | 结束进行中的游戏 | `OngoingGameController` |
| GET | `/me` | 获取当前用户信息 | `MeController` |

#### 6.0.3 WebSocket消息映射（`/app/gomoku.*`）
| 消息映射 | 说明 | 控制器 |
|---------|------|--------|
| `/gomoku.place` | 落子 | `GomokuWsController` |
| `/gomoku.resign` | 认输 | `GomokuWsController` |
| `/gomoku.ready` | 准备/取消准备 | `GomokuWsController` |
| `/gomoku.start` | 开始游戏（房主） | `GomokuWsController` |
| `/gomoku.restart` | 重新开始 | `GomokuWsController` |
| `/gomoku.resume` | 刷新重入恢复 | `GomokuResumeController` |
| `/gomoku.kick` | 房主踢出玩家 | `GomokuWsController` |

### 6.1 创建房间流程

```
HTTP POST /api/gomoku/new?mode=PVE&rule=STANDARD
    ↓
GomokuRestController.newRoom()
    ↓
检查是否已有进行中的房间（OngoingGameTracker）
    ↓
GomokuServiceImpl.newRoom()
    ↓
Redis: 创建 RoomMeta、GameStateRecord、SeatsBinding、房间索引
    ↓
记录到 OngoingGameTracker
    ↓
缓存房主用户信息
    ↓
返回 roomId
```

### 6.2 获取房间快照流程

```
HTTP GET /api/gomoku/rooms/{roomId}/view
    ↓
GomokuRestController.getRoomView()
    ↓
GomokuService.snapshot(roomId)
    ↓
从Redis聚合数据：
  - RoomMeta（房间元信息）
  - SeatsBinding（座位绑定）
  - GameStateRecord（游戏状态）
  - TurnAnchor（倒计时锚点）
  - UserProfile（用户信息缓存）
    ↓
查询WebSocket连接状态（SessionRegistry）
    ↓
构建GomokuSnapshot
    ↓
返回完整快照
```

### 6.3 WebSocket连接与恢复流程

```
WebSocket连接建立 (/game-service/ws?access_token=...)
    ↓
WebSocketSessionManager.handleSessionConnect()
    ↓
注册会话
    ↓
客户端订阅房间事件 (/topic/room.{roomId})
    ↓
客户端发送恢复请求 (/app/gomoku.resume, 携带seatKey)
    ↓
GomokuResumeController.onResume()
    ↓
如果提供seatKey，通过bindBySeatKey()绑定座位
    ↓
生成完整快照 (GomokuSnapshot)
    ↓
转换为FullSync DTO
    ↓
点对点推送 (/user/queue/gomoku.full)
```

### 6.4 落子流程

```
WebSocket消息: /app/gomoku.place
    ↓
GomokuWsController.place()
    ↓
1. 验证权限（PVE模式只有房主可以走棋）
2. 绑定座位（如果提供seatKey）
3. GomokuService.place() → 执行落子
   - 验证房间状态、回合、合法性、禁手
   - 执行落子（GomokuState.apply()）
   - 胜负判定（GomokuJudge.outcomeAfterMove()）
4. CAS保存到Redis（使用expectedStep和expectedTurn）
5. 广播 STATE 事件（增量更新，包含GomokuState和SeriesView）
6. 广播 SNAPSHOT 事件（房间全貌，包含完整GomokuSnapshot）
7. TurnClockCoordinator.syncFromState() → 同步倒计时
8. 如果PVE模式且轮到AI，延迟1-1.5秒执行AI落子
```

### 6.5 准备/开始游戏流程

```
WebSocket消息: /app/gomoku.ready
    ↓
GomokuWsController.ready()
    ↓
GomokuService.toggleReady() → 切换准备状态
    ↓
广播 SNAPSHOT 事件
```

```
WebSocket消息: /app/gomoku.start
    ↓
GomokuWsController.startGame()
    ↓
GomokuService.startGame() → 检查准备状态，切换 phase: WAITING → PLAYING
    ↓
广播 SNAPSHOT 和 STATE 事件
```

### 6.6 WebSocket事件类型

| 事件类型 | 说明 | 载荷 | 广播路径 |
|---------|------|------|---------|
| `STATE` | 游戏状态更新（增量） | `{ state: GomokuState, series: SeriesView }` | `/topic/room.{roomId}` |
| `SNAPSHOT` | 房间全貌快照（全量） | `GomokuSnapshot` | `/topic/room.{roomId}` |
| `TICK` | 倒计时更新 | `{ left: number, side: 'X'\|'O', deadlineEpochMs: number }` | `/topic/room.{roomId}` |
| `TIMEOUT` | 超时判负 | `{ side: 'X'\|'O' }` | `/topic/room.{roomId}` |
| `ERROR` | 错误消息 | `string` | `/topic/room.{roomId}` |
| `READY_STATUS` | 准备状态更新（已废弃，统一使用SNAPSHOT） | `Map<userId, ready>` | `/topic/room.{roomId}` |
| `ROOM_STATUS` | 房间状态更新（已废弃，统一使用SNAPSHOT） | `{phase: "WAITING"\|"PLAYING"}` | `/topic/room.{roomId}` |

**点对点消息**：
- `/user/queue/gomoku.seat`：座位密钥（seatKey）推送
- `/user/queue/gomoku.full`：完整同步（FullSync），用于刷新重入

---

## 数据模型与存储

**说明**：本章节描述当前五子棋游戏的数据模型和存储设计。不同游戏的数据模型可能不同，但都使用相同的Redis存储基础设施。

### 7.1 Redis键设计（五子棋）

#### 7.1.1 房间元信息
```
Key: gomoku:room:{roomId}
Type: HASH
TTL: 48小时
Fields:
  - roomId: String
  - gameId: String (当前盘gameId)
  - mode: String (PVP/PVE)
  - rule: String (STANDARD/RENJU)
  - aiPiece: String (X/O/null)
  - currentIndex: Integer
  - blackWins: Integer
  - whiteWins: Integer
  - draws: Integer
  - ownerUserId: String
  - ownerName: String
  - createdAt: Long
  - phase: String (WAITING/PLAYING)
```

#### 7.1.2 游戏状态
```
Key: gomoku:room:{roomId}:game:{gameId}:state
Type: HASH
TTL: 48小时
Fields:
  - roomId: String
  - gameId: String
  - index: Integer
  - board: String (225字符，'.'表示空，'X'/'O'表示棋子)
  - current: String (X/O)
  - lastMove: String ("x,y")
  - winner: String (X/O/DRAW/null)
  - over: Boolean
  - step: Integer (棋子数量，用于CAS)
```

#### 7.1.3 座位绑定
```
Key: gomoku:room:{roomId}:seats
Type: HASH
TTL: 48小时
Fields:
  - seatXSessionId: String (黑方用户ID)
  - seatOSessionId: String (白方用户ID)
  - seatBySession:{userId}: String (X/O)
  - readyByUserId:{userId}: Boolean
```

#### 7.1.4 回合计时锚点
```
Key: gomoku:room:{roomId}:turn
Type: HASH
TTL: 动态（根据deadline）
Fields:
  - side: String (X/O)
  - deadlineEpochMs: Long
  - turnSeq: Long
```

#### 7.1.5 座位密钥（seatKey）
```
Key: gomoku:room:{roomId}:seatKey:{seatKey}
Type: String
Value: X 或 O
TTL: 48小时
用途: 刷新重入时恢复座位绑定
```

#### 7.1.6 房间索引
```
Key: gomoku:rooms:index
Type: ZSET
Score: createdAt (时间戳)
Member: roomId
用途: 大厅列表按创建时间倒序分页
```

#### 7.1.7 用户信息缓存
```
Key: gomoku:room:{roomId}:users
Type: HASH
Field: {userId}
Value: UserProfileView (序列化)
TTL: 30分钟
用途: 缓存房间内玩家信息，避免WS场景调用Feign
```

#### 7.1.8 系列赛统计
```
Key: gomoku:room:{roomId}:series
Type: HASH
TTL: 48小时
Fields:
  - blackWins: Integer
  - whiteWins: Integer
  - draws: Integer
  - index: Integer (当前局号)
```

#### 7.1.9 用户进行中游戏
```
Key: gomoku:user:{userId}:ongoing
Type: String
Value: OngoingGameInfo (序列化)
TTL: 48小时
用途: 记录用户当前进行中的游戏，供前端"继续游戏"入口使用
```

### 7.2 领域模型（五子棋）

#### 7.2.1 Room（房间）
```java
public class Room {
    private final String id;           // 房间ID
    private final Mode mode;           // PVP/PVE
    private final Rule rule;           // STANDARD/RENJU
    private final char aiPiece;        // AI棋子颜色
    private final GomokuAI ai;         // AI实例
    private final Series series;       // 系列赛信息
    private final Map<String, Character> seatBySession;  // 会话→座位映射
    private volatile String seatXSessionId;  // 黑方用户ID
    private volatile String seatOSessionId;  // 白方用户ID
}
```

#### 7.2.2 Game（单盘游戏）
```java
public class Game {
    private String gameId;      // 游戏ID（UUID）
    private final int index;    // 局号
    private final GomokuState state;  // 游戏状态
    private volatile ScheduledFuture<?> pendingAi;  // AI任务（可取消）
}
```

#### 7.2.3 GomokuState（游戏状态）
```java
public class GomokuState {
    private final Board board;      // 棋盘（15x15）
    private char current;           // 当前执子（X/O）
    private Move lastMove;          // 上一步
    private Character winner;       // 胜者（X/O/null）
    
    public boolean over() { ... }   // 是否结束
    public void apply(Move move) { ... }  // 执行落子
}
```

#### 7.2.5 GomokuSnapshot（房间快照）
```java
public final class GomokuSnapshot {
    public final String roomId;
    public final boolean seatXOccupied;
    public final boolean seatOOccupied;
    public final String seatXUserId;
    public final String seatOUserId;
    public final UserProfileView seatXUserInfo;  // 黑棋玩家信息
    public final UserProfileView seatOUserInfo;  // 白棋玩家信息
    public final boolean seatXConnected;         // 黑棋玩家连接状态
    public final boolean seatOConnected;         // 白棋玩家连接状态
    public final String ownerUserId;             // 房主用户ID
    public final long createdAt;
    public final String mode;
    public final Character aiSide;
    public final String rule;
    public final String phase;
    public final int boardSize;
    public final char[][] cells;                 // 棋盘（15x15）
    public final Character sideToMove;
    public final long turnSeq;
    public final Long deadlineEpochMs;
    public final int round;
    public final int scoreX;
    public final int scoreO;
    public final String outcome;
    public final Map<String, Boolean> readyStatus;
}
```

#### 7.2.4 Board（棋盘）
```java
public class Board {
    public static final int SIZE = 15;
    public static final char EMPTY = '.';
    public static final char BLACK = 'X';
    public static final char WHITE = 'O';
    
    private final char[][] cells;  // 15x15数组
    
    public void place(int x, int y, char piece) { ... }
    public char get(int x, int y) { ... }
    public boolean isEmpty(int x, int y) { ... }
}
```

---

## 关键技术实现

**说明**：本章节描述game-service的通用技术实现（如CAS、倒计时系统）和五子棋特有的技术实现（如AI算法、规则判定）。通用技术可被所有游戏复用。

### 8.1 CAS（Compare-And-Swap）防并发

**问题**：多玩家同时落子可能导致状态不一致

**解决方案**：
- 使用 `step`（棋子数量）和 `current`（当前执子）作为CAS期望值
- Redis `HGETALL` + `HSET` + Lua脚本实现原子更新

**关键代码**：
```java
// GomokuWsController.place()
int expectedStep = computeExpectedStep(before.board());
char expectedTurn = before.current();
// ... 执行落子 ...
gameStateRepository.updateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, nextDeadlineMillis);
```

### 8.2 WebSocket会话管理

**说明**：WebSocket会话管理、单点登录、踢旧连接等详细实现详见单点登录文档，本文档不涉及。

**简要说明**：
- 使用 `SessionRegistry` 管理WebSocket会话
- 新连接建立时，会踢掉同一用户的旧连接
- 详细实现见 `WebSocketSessionManager` 类

### 8.3 刷新重入机制

**问题**：页面刷新后需要恢复座位绑定

**解决方案**：
- 首次坐下时，后端签发 `seatKey`（Base64URL编码的随机串）
- 前端保存 `seatKey` 到 localStorage
- 刷新后，通过 `seatKey` 恢复座位绑定

**关键代码**：
```java
// GomokuServiceImpl.issueSeatKey()
String key = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
roomRepo.setSeatKey(roomId, key, String.valueOf(s), ROOM_TTL);

// GomokuServiceImpl.bindBySeatKey()
Character seat = roomRepo.getSeatKey(roomId, seatKey);
// ... 绑定座位 ...
```

### 8.4 倒计时系统

**架构**：
- **通用引擎**：`CountdownScheduler`（业务无关，支持多业务复用）
- **业务协调器**：`TurnClockCoordinator`（五子棋业务规则）

**实现**：
- 使用Redis存储倒计时锚点（`TurnAnchor`）
- 定时任务每秒检查并广播TICK
- 超时回调执行判负

**倒计时机制说明**：
- **后端**：使用 `CountdownScheduler` 每秒计算剩余时间并广播 `TICK` 事件到前端（包含 `left`、`side`、`deadlineEpochMs`）
- **前端**：接收 `TICK` 事件并显示倒计时。前端**不会自己计时**，只是显示后端发送的剩余秒数（`left`）
- **后端停止的情况**：如果后端停止发送 `TICK` 事件（如服务重启、网络中断），前端会停留在最后一次收到的倒计时值，不会继续递减。前端可以通过检测 `TICK` 事件中断来提示用户连接异常

**关键代码**：
```java
// TurnClockCoordinator.syncFromState()
// 终局或房间状态不是PLAYING：停止计时
if (state.over() || phase != RoomPhase.PLAYING) { stop(roomId); return; }

// PVE且不计AI且轮到AI：停止计时
if (aiTurn && !aiTimed) { stop(roomId); return; }

// 启动/续上倒计时
String key = "gomoku:" + roomId;
String owner = String.valueOf(sideToMove);
String version = gomokuService.getGameId(roomId);  // 使用gameId作为版本，防止跨盘操作
long deadline = System.currentTimeMillis() + turnSeconds * 1000L;
scheduler.startOrResume(key, owner, deadline, version, (k, o, v) -> handleTimeout(k, o));
```

### 8.5 AI算法（五子棋）

**算法**：威胁优先 + αβ搜索 + 禁手过滤

**说明**：AI算法是游戏特定的，每个游戏可独立实现自己的AI策略。

**关键类**：
- `GomokuAI`：AI主类
- `Evaluator`：棋面评估函数

**延迟执行**：
- 玩家落子后，延迟1-1.5秒再执行AI落子（随机延迟，模拟人类思考时间）
- 使用专门的延迟线程池 `aiScheduler`（`ScheduledExecutorService`）实现延迟，该线程池由 `AiSchedulerConfig` 配置，与倒计时调度器分离，避免相互影响
- 使用 `gameId` 防止AI跨盘操作（如果游戏重新开始，AI任务会被取消）
- 使用 `ConcurrentHashMap<String, ScheduledFuture<?>> pendingAi` 防抖，同一房间只保留一个待执行的AI任务

**关键代码**：
```java
// GomokuWsController 注入的 aiScheduler（由 AiSchedulerConfig 提供）
private final ScheduledExecutorService aiScheduler;

// GomokuWsController.maybeScheduleAi()
// 取消该房间之前的AI任务（防止重复执行）
ScheduledFuture<?> old = pendingAi.remove(roomId);
if (old != null) old.cancel(false);
// 延迟1-1.5秒（1000ms + 0~500ms随机）
long delay = 1000 + ThreadLocalRandom.current().nextLong(501);
ScheduledFuture<?> fut = aiScheduler.schedule(() -> runAiTurn(roomId, gameIdAtSchedule, ai), delay, TimeUnit.MILLISECONDS);
pendingAi.put(roomId, fut);

// GomokuWsController.runAiTurn()
// 验证gameId，防止跨盘操作
if (!gameIdAtSchedule.equals(gomokuService.getGameId(roomId))) return;
```

### 8.6 规则判定（五子棋）

#### 8.6.1 普通规则（STANDARD）
- 五连即胜
- 棋盘已满即和棋

**关键代码**：`GomokuJudge`

#### 8.6.2 禁手规则（RENJU）
- 黑方禁手：长连、四四、三三
- 白方无禁手

**关键代码**：`GomokuJudgeRenju`

---

## 代码细节分析

**说明**：本章节主要分析当前五子棋游戏的代码实现。game-service的代码结构支持多游戏扩展，每个游戏在`games/{gameName}/`目录下独立实现。

### 9.1 后端关键类分析

#### 9.1.1 GomokuServiceImpl（核心服务类，1175行）

**职责**：
- 房间生命周期管理（创建、加入、离开、销毁、踢人）
- 游戏状态管理（落子、认输、重开、新盘）
- 座位绑定管理（分配、恢复、seatKey）
- 准备状态管理（切换、获取、重置）
- 房间状态管理（WAITING/PLAYING）
- 快照生成（`snapshot()`）
- 用户信息缓存（避免WS场景调用Feign）

**关键方法**：
- `newRoom()`：创建房间（检查进行中房间、创建Redis数据、记录OngoingGameTracker）
- `room()`：获取房间（内存优先，未命中从Redis加载并回灌到内存）
- `place()`：落子（验证房间状态、回合、合法性、禁手，执行落子，胜负判定）
- `resolveAndBindSide()`：分配/恢复座位（PVE自动分配，PVP支持指定座位）
- `leaveRoom()`：离开房间（PVE销毁房间，PVP释放座位、转移房主、清空对局状态）
- `kickPlayer()`：房主踢出玩家（仅WAITING状态，PVP模式）
- `snapshot()`：生成快照（从Redis聚合所有数据，支持多节点部署）
- `cacheUserProfile()`：缓存用户信息（避免WS场景调用Feign）

**设计模式**：
- **仓储模式**：通过Repository接口访问Redis
- **策略模式**：PVE/PVP模式不同处理逻辑
- **内存+Redis双重存储**：内存用于快速访问，Redis用于持久化和多节点共享

#### 9.1.2 GomokuWsController（WebSocket控制器，434行）

**职责**：
- 处理WebSocket消息（落子、认输、准备、开始游戏、重开）
- 广播游戏状态（STATE、SNAPSHOT、ERROR）
- AI任务调度（PVE模式）

**关键方法**：
- `place()`：处理落子（包含PVE权限检查、身份绑定、CAS保存）
- `maybeScheduleAi()`：调度AI任务（延迟1-1.5秒，使用gameId防止跨盘操作）
- `runAiTurn()`：执行AI落子（验证gameId，防止跨盘操作）
- `sendState()`：广播状态（同时发送STATE和SNAPSHOT事件）
- `broadcastSnapshot()`：广播快照（统一广播房间全貌）
- `kickPlayer()`：房主踢出玩家（仅WAITING状态，PVP模式）

**设计要点**：
- 使用 `gameId` 防止AI跨盘操作（如果游戏重新开始，AI任务会被取消）
- CAS保存状态，防止并发（使用expectedStep和expectedTurn作为期望值）
- 统一使用 `broadcastSnapshot()` 广播房间全貌（支持多节点部署）
- PVE模式权限检查：只有房主可以走棋、认输、重开

#### 9.1.3 TurnClockCoordinator（倒计时协调器，267行）

**职责**：
- 协调通用倒计时引擎与五子棋业务规则
- 注册TICK监听并恢复活跃任务
- 根据游戏状态同步倒计时
- 处理超时判负

**关键方法**：
- `onReady()`：应用启动时注册TICK监听并恢复活跃任务
- `syncFromState()`：同步倒计时（根据游戏状态决定启动/停止计时）
- `handleTimeout()`：处理超时（执行判负、CAS保存、广播TIMEOUT/STATE/SNAPSHOT）

**设计要点**：
- 使用 `gameId` 作为版本号，防止跨盘操作
- 终局、房间状态不是PLAYING、或PVE且不计AI时停止计时
- 超时后执行判负（`gomokuService.resign()`）并广播
- TICK事件每秒广播一次（包含left、side、deadlineEpochMs）

#### 9.1.4 WebSocketSessionManager（WebSocket会话管理）

**职责**：
- 监听WebSocket连接/断开事件
- 会话管理（详细实现详见单点登录文档）
- 断开时广播房间快照（更新连接状态）

**关键方法**：
- `handleSessionConnect()`：处理连接（注册会话、踢旧连接）
- `handleSessionDisconnect()`：处理断开（注销会话、广播房间快照更新连接状态）

**设计要点**：
- 连接建立时注册会话，断开时注销会话
- 断开时清理会话注册，确保快照中的连接状态正确


### 9.2 前端关键类分析

#### 9.2.0 前端路由配置（App.jsx，47行）

**路由结构**：
- `/`：首页（HomePage）
- `/lobby`：游戏大厅（LobbyPage）
- `/game/:roomId`：游戏房间（GameRoomPage）
- `/profile`：个人中心（ProfilePage）
- `/sessions`：会话监控（SessionMonitorPage，调试用）
- `*`：404页面（NotFoundPage）

**布局结构**：
- 所有路由都包裹在`<AppLayout>`中
- `AppLayout`提供：Header（顶部导航）+ Outlet（页面内容）+ GlobalChat（全局聊天）

#### 9.2.1 useGomokuGame（游戏逻辑Hook，748行）

**职责**：
- 管理游戏状态（棋盘、回合、比分、倒计时等）
- WebSocket连接管理
- 处理WebSocket事件（STATE、SNAPSHOT、TICK、ERROR）
- 提供操作方法（落子、认输、准备、开始游戏）

**关键状态**：
- `board`：棋盘（15x15数组）
- `sideToMove`：当前执子
- `mySide`：我的座位（X/O）
- `countdown`：倒计时
- `readyStatus`：准备状态
- `roomPhase`：房间状态（WAITING/PLAYING）

**关键方法**：
- `handleRoomEvent()`：处理房间事件（STATE、SNAPSHOT、TICK、ERROR等）
- `handleFullSync()`：处理完整同步（更新所有游戏状态、房间状态、座位信息等）
- `placeStone()`：落子（检查连接状态、房间状态、回合等）
- `updateGameState()`：更新游戏状态（棋盘、回合、胜负等）
- `updateSeriesInfo()`：更新系列信息（比分、局号等）

**设计要点**：
- 首屏通过HTTP获取快照（不依赖WebSocket）
- WebSocket连接后发送恢复请求（携带seatKey）
- 统一使用 `handleFullSync()` 处理快照（支持刷新重入）
- 双重更新机制：STATE（增量）+ SNAPSHOT（全量）

#### 9.2.2 GameRoomPage（游戏房间页面）

**职责**：
- 渲染游戏界面（棋盘、玩家信息、聊天、系统日志）
- 处理用户交互（落子、认输、准备、开始游戏）
- 显示游戏状态（回合、比分、倒计时）

**关键组件**：
- `GomokuBoard`：棋盘组件（渲染15x15格子、星位、坐标、获胜线）
- `PlayerCard`：玩家卡片（头像、名字、座位、倒计时、准备状态）
- `GameChatPanel`：游戏聊天面板
- `SystemInfoPanel`：系统日志面板

**设计要点**：
- 使用 `useGomokuGame` Hook管理游戏逻辑
- 检测游戏结束瞬间显示胜利弹窗
- 根据房间状态（WAITING/PLAYING）显示不同UI

#### 9.2.3 gomokuSocket（WebSocket服务，255行）

**职责**：
- WebSocket连接管理（SockJS + STOMP）
- 订阅房间事件、seatKey、完整同步
- 发送消息（落子、认输、准备、开始游戏、恢复）

**关键方法**：
- `connectWebSocket()`：建立连接（通过URL参数传递token，token获取详见单点登录文档）
- `subscribeRoom()`：订阅房间事件（`/topic/room.{roomId}`）
- `subscribeSeatKey()`：订阅seatKey（`/user/queue/gomoku.seat`）
- `subscribeFullSync()`：订阅完整同步（`/user/queue/gomoku.full`）
- `subscribeKicked()`：订阅被踢事件（`/user/queue/gomoku.kicked`）
- `sendResume()`：发送恢复请求（携带seatKey）
- `sendPlace()`：发送落子（携带seatKey）
- `sendResign()`：发送认输（携带seatKey）
- `sendRestart()`：发送重开（携带seatKey）
- `sendReady()`：发送准备（携带seatKey）
- `sendStartGame()`：发送开始游戏（携带seatKey）
- `sendKick()`：发送踢人请求（房主操作）
- `disconnectWebSocket()`：断开连接（清理所有订阅）
- `isConnected()`：检查连接状态

**设计要点**：
- 通过URL参数传递token（SockJS握手无法在header中传递）
- 统一错误处理
- 使用Map管理订阅，支持取消订阅
- 连接超时设置（10秒）

**说明**：token获取、认证流程的详细实现详见单点登录文档，本文档不涉及。

#### 9.2.4 OngoingGameContext（进行中游戏上下文）

**职责**：
- 管理用户当前进行中的游戏状态
- 提供刷新和结束游戏的方法

**关键状态**：
- `loading`：是否正在加载
- `data`：进行中游戏数据（hasOngoing、gameType、roomId、title）
- `error`：错误信息

**关键方法**：
- `refresh()`：刷新进行中游戏状态
- `end(roomId)`：结束当前游戏

#### 9.2.6 LobbyPage（游戏大厅页面，591行）

**职责**：
- 展示游戏模式选择（人机对战、创建房间、在线匹配）
- 展示在线房间列表（分页、刷新、加载更多）
- 处理房间创建、加入、进入

**关键功能**：
- **人机对战**：创建PVE房间，选择规则（STANDARD/RENJU）
- **创建房间**：创建PVP房间，选择规则
- **在线匹配**：匹配功能（占位，待实现）
- **房间列表**：显示在线PVP房间，支持加入/观战
- **进入房间**：通过房间ID直接进入

**关键状态**：
- `rooms`：房间列表
- `roomsCursor`：分页游标
- `roomsHasMore`：是否还有更多房间
- `refreshingRooms`：是否正在刷新
- `loadingMoreRooms`：是否正在加载更多

#### 9.2.7 HomePage（首页，336行）

**职责**：
- 展示欢迎页面
- 展示热门游戏推荐
- 显示进行中游戏提示（如果有）

**关键功能**：
- **轮播图**：展示游戏推荐（五子棋、中国跳棋、巷道狂奔等）
- **进行中游戏卡片**：如果有进行中的游戏，显示"继续游戏"入口
- **游戏分类**：热门游戏、新游戏、策略/棋牌等分类展示

#### 9.2.8 apiClient（HTTP客户端，103行）

**职责**：
- 封装HTTP请求
- 统一错误处理

**关键方法**：
- `authenticatedFetch()`：带token的fetch
- `authenticatedJsonFetch()`：带token的JSON请求
- `get()`：GET请求
- `post()`：POST请求
- `put()`：PUT请求
- `del()`：DELETE请求

**设计要点**：
- 统一解析ApiResponse格式
- 统一错误处理

**说明**：token获取、401处理的详细实现详见单点登录文档，本文档不涉及。

#### 9.2.9 gameApi（游戏API服务，201行）

**职责**：
- 封装所有游戏相关的HTTP API调用

**关键方法**：
- `createRoom()`：创建房间
- `getMe()`：获取当前用户信息
- `getOngoingGame()`：获取进行中的游戏
- `endOngoingGame()`：结束进行中的游戏
- `joinRoom()`：加入房间
- `leaveRoom()`：离开房间
- `listGomokuRooms()`：获取房间列表（分页）
- `getRoomView()`：获取房间完整快照
- `getUserInfos()`：批量获取用户信息
- `getUserInfo()`：获取单个用户信息

### 9.3 数据流转细节

#### 9.3.0 前端状态管理

**状态层次**：
1. **全局状态**（Context）：
   - `OngoingGameContext`：进行中游戏状态
2. **页面级状态**（useState）：
   - `GameRoomPage`：UI状态（弹窗、消息提示等）
   - `LobbyPage`：房间列表、模态框状态
3. **业务逻辑状态**（自定义Hook）：
   - `useGomokuGame`：游戏状态（棋盘、回合、倒计时等）
   - `useOngoingGame`：从OngoingGameContext提取进行中游戏

**状态更新流程**：
```
WebSocket事件 → handleRoomEvent() → 更新Hook状态 → 触发组件重新渲染
HTTP响应 → 更新Context/Hook状态 → 触发组件重新渲染
用户操作 → 调用Hook方法 → 发送WebSocket/HTTP请求 → 等待响应 → 更新状态
```

#### 9.3.1 落子数据流

```
前端点击格子
    ↓
useGomokuGame.placeStone()
    ↓
gomokuSocket.sendPlace(roomId, x, y, side, seatKey)
    ↓
STOMP: /app/gomoku.place
    ↓
GomokuWsController.place()
    ↓
1. 获取当前状态（before）
2. 计算CAS期望值（expectedStep, expectedTurn）
3. GomokuService.place() → 执行落子
   - 验证房间状态、回合、合法性、禁手
   - GomokuState.apply() → 更新棋盘
   - GomokuJudge.outcomeAfterMove() → 胜负判定
   - 如果未结束，切换回合
4. buildRecord() → 构建GameStateRecord
5. persistStateAtomically() → CAS保存到Redis
6. sendState() → 广播STATE事件
   - 包含GomokuState和SeriesView
7. broadcastSnapshot() → 广播SNAPSHOT事件
   - 包含完整房间快照
8. TurnClockCoordinator.syncFromState() → 同步倒计时
    ↓
前端收到STATE事件
    ↓
useGomokuGame.handleRoomEvent()
    ↓
updateGameState() → 更新棋盘、回合、胜负
    ↓
GameRoomPage重新渲染
```

#### 9.3.2 快照生成流程（后端）

```
GomokuService.snapshot(roomId)
    ↓
1. assembleRoomView() → 组装房间视图
   - 从Redis读取RoomMeta
   - 从Redis读取SeatsBinding
   - 从Redis读取GameStateRecord（当前盘）
   - 从Redis读取TurnAnchor
2. 查询用户信息（优先读缓存）
3. 查询WebSocket连接状态（SessionRegistry）
4. 构建GomokuSnapshot
   - 房间信息（roomId、mode、rule、phase、createdAt）
   - 座位信息（seatXOccupied、seatOOccupied、seatXUserId、seatOUserId）
   - 用户信息（seatXUserInfo、seatOUserInfo）
   - 连接状态（seatXConnected、seatOConnected）
   - 棋盘（cells：15x15数组）
   - 游戏状态（sideToMove、round、scoreX、scoreO、outcome）
   - 倒计时（turnSeq、deadline）
   - 准备状态（readyStatus）
    ↓
返回GomokuSnapshot
```

#### 9.3.3 前端快照处理流程

```
收到SNAPSHOT事件（WebSocket）或HTTP响应
    ↓
useGomokuGame.handleRoomEvent() 或 handleFullSync()
    ↓
handleFullSync(snap)
    ↓
1. 更新棋盘（buildBoardFromPayload）
   - 解析cells数组或board字符串
   - 更新board状态（15x15数组）
2. 更新游戏状态
   - sideToMove → 当前执子
   - round → 局号
   - scoreX/scoreO → 比分
   - outcome → 胜负结果
3. 更新房间状态
   - phase → 房间状态（WAITING/PLAYING）
   - readyStatus → 准备状态
   - mode → 游戏模式（PVP/PVE）
   - aiSide → AI棋子颜色
4. 更新座位信息
   - seatXUserId/seatOUserId → 座位用户ID
   - seatXUserInfo/seatOUserInfo → 用户详细信息
   - seatXConnected/seatOConnected → 连接状态
5. 更新我的座位
   - mySide → 我的座位（X/O）
   - seatKey → 保存到localStorage
6. 更新倒计时
   - turnSeq → 回合序号
   - deadlineEpochMs → 截止时间
    ↓
触发组件重新渲染
    ↓
GameRoomPage更新UI（棋盘、玩家信息、准备状态等）
```

---

## 前端详细分析

**说明**：本章节描述前端（game-hub-web）中与五子棋游戏相关的实现。前端同样采用模块化设计，每个游戏可独立实现自己的页面和逻辑。

### 10.0 前端页面功能

#### 10.0.1 HomePage（首页）
**功能**：
- 欢迎页面
- 轮播图展示热门游戏
- 进行中游戏提示（如果有）
- 游戏分类展示（热门游戏、新游戏、策略/棋牌）

**关键交互**：
- 点击"五子棋"卡片 → 跳转到 `/lobby`
- 点击"继续游戏" → 跳转到 `/game/{roomId}`

#### 10.0.2 LobbyPage（游戏大厅）
**功能**：
- **人机对战**：创建PVE房间
  - 选择规则（STANDARD/RENJU）
  - 创建后跳转到游戏房间
- **创建房间**：创建PVP房间
  - 选择规则（STANDARD/RENJU）
  - 创建后跳转到游戏房间
- **在线匹配**：匹配功能（占位，待实现）
- **房间列表**：
  - 显示在线PVP房间（过滤PVE、已满、已删除）
  - 支持刷新、分页加载更多
  - 显示房间信息（房主、规则、状态、人数）
  - 支持加入/观战操作
- **进入房间**：通过房间ID直接进入

**关键状态管理**：
- 房间列表状态（rooms、cursor、hasMore）
- 模态框状态（pveModalOpen、createModalOpen、enterModalOpen）
- 加载状态（refreshingRooms、loadingMoreRooms）

#### 10.0.3 GameRoomPage（游戏房间）
**功能**：
- **棋盘渲染**：15x15棋盘，支持落子、显示最后一步、显示获胜线
- **玩家信息**：显示自己和对手的头像、名字、座位、倒计时、准备状态
- **游戏状态**：显示回合、当前执子、比分、游戏状态
- **操作按钮**：
  - 准备/取消准备（WAITING状态）
  - 开始游戏（房主，WAITING状态，所有玩家已准备）
  - 认输（PLAYING状态）
  - 离开房间
- **聊天面板**：游戏内聊天（本地，未接入后端）
- **系统日志**：显示游戏事件日志

**关键交互**：
- 点击棋盘格子 → 落子（需满足：PLAYING状态、我的回合、未结束）
- 点击准备按钮 → 切换准备状态
- 点击开始游戏 → 开始游戏（仅房主）
- 点击认输 → 认输确认 → 执行认输
- 点击离开房间 → 确认 → 离开并返回大厅

#### 10.0.4 ProfilePage（个人中心）
**功能**：占位页面，待实现
- 未来会展示用户资料、战绩、偏好设置等

#### 10.0.5 SessionMonitorPage（会话监控）
**功能**：调试用页面
- 显示所有在线用户的会话信息
- 每2秒轮询一次
- 显示会话状态（ACTIVE、KICKED、EXPIRED）

#### 10.0.6 NotFoundPage（404页面）
**功能**：404错误页面
- 提示页面不存在
- 提供返回首页和游戏大厅的链接

### 10.1 前端组件分析

#### 10.1.1 AppLayout（应用布局）
**职责**：
- 提供统一的页面布局
- 包含Header、Outlet（页面内容）、GlobalChat

**结构**：
```jsx
<OngoingGameProvider>
  <div className="app-shell">
    <Header />
    <main className="app-content">
      <Outlet />  {/* 页面内容 */}
    </main>
    <GlobalChat />
  </div>
</OngoingGameProvider>
```

#### 10.1.2 Header（顶部导航栏）
**功能**：
- 显示用户信息（头像、名字）
- 显示进行中游戏提示（如果有）

**关键逻辑**：
- 从`OngoingGameContext`获取进行中游戏
- 点击"继续游戏"跳转到游戏房间

**说明**：用户信息获取的详细实现详见单点登录文档，本文档不涉及。

#### 10.1.3 GlobalChat（全局聊天）
**功能**：
- 全局聊天面板（可折叠）
- 本地消息存储（localStorage）
- 支持发送消息

**状态管理**：
- 折叠状态保存到localStorage
- 消息列表存储在组件状态中


### 10.2 前端服务层分析

#### 10.2.1 apiClient（HTTP客户端）
**功能**：
- 封装HTTP请求
- 统一处理401未授权
- 统一错误处理

**关键实现**：
- `authenticatedFetch()`：带token的fetch
- `authenticatedJsonFetch()`：带token的JSON请求，自动解析ApiResponse格式
- 支持GET、POST、PUT、DELETE方法

**说明**：token获取、认证流程的详细实现详见单点登录文档，本文档不涉及。

#### 10.2.2 gameApi（游戏API服务）
**功能**：
- 封装所有游戏相关的HTTP API调用
- 统一处理API响应格式（`{ code, message, data }`）

**API列表**：
- `createRoom()`：创建房间
- `getMe()`：获取当前用户信息
- `getOngoingGame()`：获取进行中的游戏
- `endOngoingGame()`：结束进行中的游戏
- `joinRoom()`：加入房间
- `leaveRoom()`：离开房间
- `listGomokuRooms()`：获取房间列表（分页）
- `getRoomView()`：获取房间完整快照
- `getUserInfos()`：批量获取用户信息
- `getUserInfo()`：获取单个用户信息

#### 10.2.3 gomokuSocket（WebSocket服务）
**功能**：
- WebSocket连接管理（SockJS + STOMP）
- 订阅房间事件、seatKey、完整同步
- 发送游戏操作消息

**连接流程**：
1. 获取token（详见单点登录文档）
2. 通过URL参数传递token（`/game-service/ws?access_token=...`）
3. 建立SockJS连接
4. 使用STOMP协议通信
5. 设置连接头（Authorization: Bearer token）

**说明**：token获取、认证流程的详细实现详见单点登录文档，本文档不涉及。

**订阅管理**：
- 使用Map存储订阅对象
- 支持取消订阅
- 连接断开时清理所有订阅

### 10.3 前端状态管理

#### 10.3.1 全局状态（Context）

**OngoingGameContext**：
- `loading`：是否正在加载
- `data`：进行中游戏数据（hasOngoing、gameType、roomId、title、updatedAt）
- `error`：错误信息
- `refresh()`：刷新进行中游戏状态
- `end(roomId)`：结束当前游戏

#### 10.3.2 业务状态（自定义Hook）

**useGomokuGame**：
- 管理游戏相关所有状态（棋盘、回合、比分、倒计时、准备状态等）
- 管理WebSocket连接
- 处理WebSocket事件
- 提供游戏操作方法

**useOngoingGame**：
- 从OngoingGameContext提取进行中游戏状态
- 提供便捷的进行中游戏操作方法

### 10.4 前端数据流

#### 10.4.1 初始化流程

```
应用启动（main.jsx）
    ↓
前端应用初始化
    ↓
OngoingGameProvider 初始化
    ↓
OngoingGameContext.fetchOngoingGame() → 查询进行中游戏
    ↓
App 渲染
    ↓
根据路由渲染对应页面
```

#### 10.4.2 进入游戏房间流程

```
用户访问 /game/{roomId}
    ↓
路由检查
    ↓
GameRoomPage 组件挂载
    ↓
useGomokuGame({ roomId }) 初始化
    ↓
1. HTTP GET /api/gomoku/rooms/{roomId}/view
   → getRoomView(roomId)
   → handleFullSync(snap)
   → 更新所有游戏状态
   → 渲染首屏
    ↓
2. WebSocket 连接
   → connectWebSocket()
   → 建立SockJS连接
   → STOMP连接
   → 注册会话、踢旧连接
    ↓
3. 订阅事件
   → subscribeRoom() → 监听房间事件
   → subscribeSeatKey() → 接收seatKey
   → subscribeFullSync() → 接收完整同步
    ↓
4. 发送恢复请求
   → sendResume(roomId, seatKey)
   → 从localStorage读取seatKey
   → 后端返回FullSync
   → handleFullSync() → 更新状态
```

#### 10.4.3 状态更新流程

```
WebSocket事件到达
    ↓
subscribeRoom 回调
    ↓
handleRoomEvent(evt)
    ↓
根据事件类型分发：
  - STATE → updateGameState() → 更新棋盘、回合、胜负
  - SNAPSHOT → handleFullSync() → 更新房间全貌
  - TICK → setCountdown() → 更新倒计时
  - TIMEOUT → 更新游戏状态（判负）
  - ERROR → onMessage() → 显示错误提示
    ↓
触发组件重新渲染
    ↓
GameRoomPage 更新UI
```

### 10.5 前端关键技术

#### 10.5.1 首屏优化
**策略**：
- 首屏通过HTTP获取房间快照（不依赖WebSocket）
- WebSocket连接异步建立
- 即使WebSocket未连接，也能看到房间状态

**实现**：
```javascript
// useGomokuGame.js
useEffect(() => {
  // 首屏通过HTTP获取快照
  getRoomView(roomId).then(handleFullSync)
}, [roomId])
```

#### 10.5.2 状态同步机制
**双重更新**：
- **STATE事件**：增量更新（棋盘、回合、比分）
- **SNAPSHOT事件**：全量更新（房间全貌，包括座位、准备状态、连接状态等）

**优势**：
- STATE事件轻量，适合频繁更新
- SNAPSHOT事件完整，确保状态一致性
- 前端可选择性使用，优化性能

#### 10.5.3 刷新恢复机制
**流程**：
1. 首次坐下时，后端签发seatKey
2. 前端收到seatKey，保存到localStorage
3. 页面刷新后，从localStorage读取seatKey
4. 发送恢复请求时携带seatKey
5. 后端通过seatKey恢复座位绑定

**实现**：
```javascript
// gomokuSocket.js
subscribeSeatKey((seatKey, side) => {
  seatKeyRef.current = seatKey
  // 保存到localStorage（实际代码中可能在其他地方保存）
})

// 发送恢复请求
sendResume(roomId, seatKeyRef.current) // 从localStorage读取
```

#### 10.5.4 错误处理
**统一错误处理**：
- HTTP请求：`apiClient`统一处理错误响应
- WebSocket：`gomokuSocket`统一处理连接错误
- 用户提示：通过`onMessage`回调统一显示错误提示

**错误类型**：
- 网络错误：提示"网络连接失败"
- 业务错误：显示后端返回的错误消息

---

## 后续改进计划

### 11.1 当前存在的问题

#### 11.1.1 代码组织
- **GomokuServiceImpl过大**（1175行）：我计划将其拆分为多个服务
  - 房间管理服务（RoomManagementService）
  - 游戏状态服务（GameStateService）
  - 座位管理服务（SeatManagementService）
- **内存与Redis双重存储**：可能导致不一致
  - 当前实现：内存优先，未命中从Redis加载并回灌到内存
  - 我的改进计划：统一以Redis为准，内存仅作缓存，定期同步或使用事件驱动更新

#### 11.1.2 性能问题
- **findRoomByUserId()效率低**：遍历所有房间
  - 我的改进计划：维护用户→房间的索引（Redis SET）
- **AI任务调度**：使用内存ConcurrentHashMap，多节点部署可能重复执行
  - 我的改进计划：使用Redis分布式锁

#### 11.1.3 错误处理
- **部分异常被忽略**：`catch (Exception ignore)`
  - 我的改进计划：至少记录日志，便于排查问题
- **CAS失败无重试**：可能导致落子失败
  - 我的改进计划：实现重试机制，提高成功率

#### 11.1.4 代码问题
- **useGomokuGame Hook过大**（748行）：我计划将其拆分
  - 状态管理Hook
  - WebSocket管理Hook
  - 操作方法Hook
- **GameRoomPage过大**：我计划将其拆分为多个组件
  - 棋盘组件
  - 玩家面板组件
  - 聊天组件

### 11.2 后续优化方向

#### 11.2.1 架构优化
1. **引入领域事件**：房间创建、玩家加入、游戏结束等发布事件，解耦业务逻辑
2. **引入CQRS**：读写分离，查询优化，提升性能
3. **引入消息队列**：异步处理AI落子、状态同步，提升响应速度

#### 11.2.2 性能优化
1. **Redis Pipeline**：批量操作减少网络往返，提升性能
2. **本地缓存**：用户信息、房间元信息（Caffeine），减少Redis访问
3. **WebSocket连接池**：复用连接，降低资源消耗

#### 11.2.3 可观测性
1. **指标监控**：房间数、在线玩家数、对局数、AI响应时间，便于监控系统健康状态
2. **链路追踪**：落子请求的完整链路，便于排查问题
3. **日志聚合**：统一日志格式，便于排查和分析

#### 11.2.4 测试
1. **单元测试**：规则判定、AI算法，确保核心逻辑正确
2. **集成测试**：房间生命周期、落子流程，确保端到端功能正常
3. **压力测试**：并发落子、大量房间，验证系统承载能力

---

## 总结

### 优点

#### 后端优点
1. **分层清晰**：接口层、服务层、领域层、基础设施层分离
2. **状态管理完善**：Redis持久化 + 内存缓存
3. **实时通信**：WebSocket + STOMP实现实时对局
4. **防并发**：CAS机制防止并发落子
5. **WebSocket会话管理**：支持会话注册和断开处理
6. **倒计时系统**：通用引擎 + 业务协调器设计，支持多业务复用

#### 前端优点
1. **组件化设计**：页面、组件、Hooks分离清晰
2. **状态管理**：Context + Hooks，状态集中管理
3. **路由管理**：统一路由配置
4. **错误处理**：统一错误处理和用户提示
5. **用户体验**：首屏HTTP渲染 + WebSocket实时更新

### 待改进

#### 后端待改进
1. **代码拆分**：核心类过大（GomokuServiceImpl 1175行），需要拆分
2. **性能优化**：部分查询效率低（findRoomByUserId遍历所有房间），需要优化
3. **错误处理**：部分异常被忽略，需要完善
4. **测试覆盖**：缺少测试，需要补充

#### 前端待改进
1. **代码拆分**：useGomokuGame Hook过大（841行），GameRoomPage过大
2. **错误处理**：部分错误仅console.error，需要统一错误提示
3. **性能优化**：大量状态更新可能导致重复渲染
4. **类型安全**：缺少TypeScript，建议迁移

### 技术亮点

#### 后端技术亮点
1. **CAS防并发**：使用step和current作为CAS期望值
2. **倒计时系统**：通用引擎 + 业务协调器设计
3. **刷新重入**：seatKey机制支持页面刷新恢复
4. **WebSocket会话管理**：支持会话注册和断开处理
5. **快照机制**：SNAPSHOT事件统一广播房间全貌，支持多节点部署

#### 前端技术亮点
1. **首屏优化**：HTTP获取快照，不依赖WebSocket
2. **状态同步**：STATE（增量）+ SNAPSHOT（全量）双重更新机制
3. **刷新恢复**：seatKey + FullSync支持页面刷新后恢复状态
4. **实时更新**：WebSocket事件驱动，实时更新棋盘、倒计时、准备状态
5. **用户体验**：游戏结束弹窗、禁手提示、连接状态显示等细节完善

---

**文档版本**：v1.0  
**最后更新**：2025年  
**维护者**：开发团队

