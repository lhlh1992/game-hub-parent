# 🎯 Game Hub Project Overall Blueprint and Architecture Hierarchy

> Document Purpose: Provide the project's final target form, service and functionality blueprint, combined with existing code to provide an actionable architecture hierarchy view (including core class responsibilities of each service), facilitating unified planning and expansion.

---

## 1. Vision and Positioning

- **Product Positioning**: International real-time battle "mini-game platform", covering board games, casual competitive, puzzle battles and other multi-category games, and providing unified social/operations capability center.
- **Core Value**:
  - **Players**: One-stop lobby, matching, chat rooms, personal growth system, seamless cross-device experience, able to freely switch between multiple mini-games.
  - **Operations**: Fine-grained user management, permission configuration, online session monitoring, activity and content delivery, supporting multi-game joint operations.
  - **Technology**: Cloud-native microservices, unified identity authentication, observability, rapid integration of "any type of mini-game engine".

---

## 2. Target Business Blueprint (Final State)

| Layer | Service / Capability | Key Responsibilities | Main Technologies/Protocols |
|-------|---------------------|---------------------|---------------------------|
| Experience Layer | Web / Mobile BFF, Frontend SPA | Game lobby, personal center, admin console, spectating, IM panel | React/Vue, WebSocket, REST |
| Access Layer | `gateway-service` | JWT validation, WS negotiation, routing, CORS, rate limiting | Spring Cloud Gateway, OAuth2 Resource Server |
| Identity Domain | Keycloak / `auth-service` | Registration, login, SSO, social login, role management | Keycloak, OpenID Connect |
| Session Domain | `session-common`, `session-kafka-notifier` | Record online sessions, mutual kick notifications, WS Session mapping | Redis, Kafka |
| Game Domain | `game-lobby-service` (planned), `game-service-core`, `game-engine-<game>` (Gomoku/Chess/Card/Casual Competitive, etc.) | Matching/rooms, rule adjudication, countdown, AI, records, gameplay orchestration | Spring Boot, STOMP, Redis, PostgreSQL |
| Social Domain | `chat-service` | Lobby/room chat, private chat, channels, message persistence, mute, system notification push | WebSocket, Kafka, Redis, PostgreSQL |
| User Domain | `system-service` + `user-profile-service` (planned) | User profiles, preferences, permissions, admin operations, activity configuration | Spring Boot, PostgreSQL |
| Operations Domain | `admin-console`, `analytics-service` (planned) | Menu configuration, real-time online, alerts, BI reports | Grafana, ClickHouse/Elastic |
| Data Domain | `rating-service`, `record-service`, `assets-service` | Elo rating, game replay, historical records, asset storage | PostgreSQL, MinIO/S3 |
| Infrastructure | Observability, CI/CD, Configuration Center, Secret Management | Logs/Tracing/Metrics, automated pipelines, configuration governance, secret storage | Prometheus, Grafana, OpenTelemetry, Jaeger, Loki, GitHub Actions, Vault/K8s Secret |

Final State Interaction (Simplified):
1. Player completes Keycloak login on frontend → Gateway validates JWT → Injects user context.
2. Lobby creates room based on player selection or matching strategy → Routes to corresponding `game-engine-<game>` (Gomoku, casual shooter, card games, etc.) → Pushes state through unified WS Hub.
3. Chat Service reuses room and channel IDs, provides lobby/room/private chat messages, integrates with chat components in different games.
4. System/Admin services view online sessions in real-time (Session service + Gateway events), unified operations across multiple games.
5. All events broadcast through Kafka to subscribers like records, points, BI, achieving decoupled expansion.

---

## 3. Functionality Map and Phase Results

- **Player Journey**: Register/Login → Lobby browsing → Match/Create room → In-game battle + Room chat → View records/replays after completion → Personal homepage (records, badges, friends).
- **Social Journey**: Friends/Follow → Private chat/Group chat → Channels/Teams → Tournament chat → Voice/text extensions in rooms.
- **Operations Journey**: Admin login → Menu/Permission management → View online sessions/Manual kick → Activity configuration/Announcements → Data reports.

### 3.1 Currently Completed Features

**Frontend (game-hub-web)**:
- ✅ React SPA application (Vite + React 19.2 + React Router 7.9)
- ✅ Homepage (HomePage): Game recommendations, ongoing game prompts
- ✅ Game Lobby (LobbyPage): Create room, join room, room list, enter room
- ✅ Game Room (GameRoomPage): Gomoku game interface, real-time communication, countdown display
- ✅ Session Monitor Page (SessionMonitorPage): For debugging, view online sessions
- ✅ Keycloak authentication integration: Login, logout, token refresh, route protection
- ✅ WebSocket real-time communication: SockJS + STOMP, supports reconnection
- ✅ Ongoing game tracking: OngoingGameContext, supports "Continue Game" functionality

**Backend Services**:
- ✅ **gateway**: JWT validation, WebSocket forwarding, OAuth2 client, session management, kick functionality, token blacklist
- ✅ **system-service**: User management (CRUD), PostgreSQL persistence, Keycloak event listening, session monitoring API, user synchronization, friend system, notification system, file storage
- ✅ **game-service**: Complete Gomoku functionality (PVP/PVE), WebSocket real-time gameplay, countdown system, AI engine, room management, CAS concurrency control
- ✅ **chat-service**: Lobby/room chat, private messages, system notification push, message persistence (Redis + PostgreSQL), WebSocket real-time communication

**Infrastructure**:
- ✅ Redis: Room state, game state, session management, countdown state
- ✅ PostgreSQL: system-service user data persistence (sys_user, sys_user_profile, sys_role, sys_permission, etc.)
- ✅ Kafka: Session event publishing/subscription (session-kafka-notifier)
- ✅ Docker Compose: Local development environment configuration (Keycloak, PostgreSQL, Redis, Kafka, all services)

**Authentication and Authorization**:
- ✅ Keycloak SSO: OAuth2/OIDC standard protocol
- ✅ Gateway JWT validation: Unified entry point authentication
- ✅ WebSocket authentication: Pass token via URL parameters, backend validation
- ✅ Session management: Login session registration, WebSocket session mapping, multi-device mutual kick

### 3.2 Uncompleted Features (Planned)

**Services**:
- ✅ **chat-service**: Chat service implemented, supports lobby/room chat, private chat, system notification push, frontend integrated
- ❌ **game-lobby-service**: Matching service (planned), currently Lobby functionality is cohesive in game-service
- ❌ **rating-service**: Record service (planned), currently records only exist in Redis, not persisted
- ❌ **record-service**: Game record service (planned), replay functionality not implemented

**Features**:
- ❌ **Matching functionality**: Frontend has placeholder UI, backend has not implemented matching algorithm
- ❌ **Chat functionality**: Frontend has GlobalChat component, backend has not implemented chat service
- ❌ **Record persistence**: Currently only in Redis, not written to PostgreSQL
- ❌ **Admin management interface**: Only session monitoring page, missing menu/permission configuration interface
- ❌ **Observability**: Prometheus/Grafana not integrated, missing metrics monitoring
- ❌ **K8s deployment**: Only Docker Compose, Kubernetes not configured

**Next Priority Items**: Chat room MVP, matching service, record persistence, admin menu/permission management, observability integration.

---

## 4. Deployment and Data Flow

### 4.1 Current Deployment Status

**Development Environment (Docker Compose)**:
- ✅ **Keycloak**: Identity authentication service (port 8180)
- ✅ **PostgreSQL**: Database (system-service user data, Keycloak data)
- ✅ **Redis**: Cache and state storage (rooms, game state, sessions)
- ✅ **Kafka**: Event bus (session events)
- ✅ **gateway**: API gateway (port 8080)
- ✅ **game-service**: Game service (port 8081)
- ✅ **system-service**: User service (port 8082)
- ✅ **chat-service**: Chat service (port 8083)
- ✅ **game-hub-web**: Frontend application (Vite dev server, port 5173)

**Production Environment**:
- ❌ Kubernetes deployment: Not configured
- ❌ Observability: Prometheus/Grafana not integrated
- ❌ CI/CD: Automated pipeline not configured

### 4.2 Current Data Storage Status

**Redis** (In Use):
- Room metadata (RoomMeta)
- Game state (GameStateRecord)
- Seat binding (SeatsBinding)
- Countdown state (CountdownState)
- Session registry (SessionRegistry)
- User information cache (UserProfile)

**PostgreSQL** (In Use):
- system-service: User table (sys_user), user extension table (sys_user_profile), role table (sys_role), permission table (sys_permission), friend table (friend_request, user_friend), notification table (sys_notification), etc.
- chat-service: Chat message table (chat_message), chat session table (chat_session), session member table (chat_session_member)
- Keycloak: Authentication-related tables (automatically managed by Keycloak)

**Kafka** (In Use):
- Session events (SessionInvalidatedEvent): Login, logout, kick and other events

**In Use (chat-service)**:
- ✅ PostgreSQL: Chat message history (`chat_message`, `chat_session`, `chat_session_member` tables)

**In Use (system-service)**:
- ✅ MinIO: Avatar, game replay, activity material file storage (implemented)

**Not Used**:
- ❌ MongoDB: Chat history (planned, currently using PostgreSQL)
- ❌ PostgreSQL record tables: Game records, leaderboards (planned)

### 4.3 Data Flow Planning (Future Planning)

- **Environment Evolution**: Dev (Docker Compose) → Stage (K8s multi-replica) → Prod (cross-region disaster recovery)
- **Data Persistence**:
  - PostgreSQL: Users, permissions, records, chat history cold data (or expand MongoDB)
  - MinIO/S3: Avatars, game record files, activity materials
- **Observability**: Full-link Trace, business metrics (active rooms, WS connections, message throughput), security audit (key admin operations)

---

## 4.5 Architecture Selection: Why Choose Microservices

This project adopts **microservices architecture** rather than monolithic architecture, mainly based on the following considerations:

### 4.5.1 Business Characteristics Determine

- **Multiple Game Types**: Platform plans to support Gomoku, Chess, Card games and other multiple games, each game's rules, AI, state management differ significantly, requiring independent evolution.
- **Different Scaling Needs**: Game services (high concurrency, real-time) and user services (data persistence, permission management) have different scaling strategies, monolithic architecture cannot accommodate both.
- **Independent Deployment Needs**: Game logic updates should not affect user management, authentication and other core services, microservices support independent deployment and rollback.

### 4.5.2 Technical Characteristics Determine

- **Technology Stack Differences**: Game services need WebSocket real-time communication, Redis state management; user services need PostgreSQL persistence, Keycloak integration. Monolithic architecture cannot unify technology selection.
- **Resource Isolation**: Game match CPU/memory consumption and user query database connection consumption need isolation to avoid mutual impact.
- **Fault Isolation**: Game service exceptions should not affect user login, permission management and other core functions, microservices provide natural fault boundaries.

### 4.5.3 Team Collaboration Determines

- **Parallel Development**: Different teams can develop game services, user services, chat services in parallel, reducing code conflicts and dependencies.
- **Technical Debt Isolation**: Rapid iteration of game logic will not pollute code quality of stable modules like user management.

### 4.5.4 Trade-off Explanation

- **Complexity Increase**: Inter-service communication, distributed transactions, service discovery bring additional complexity, but complexity is controlled through Gateway unified entry, Kafka event decoupling, Redis shared state, etc.
- **Operations Cost**: Requires Docker Compose/K8s deployment, service monitoring, log aggregation, etc., but these are standard capabilities of cloud-native architecture.

**Conclusion**: For multi-game platform scenarios with clear business boundaries and large differences in scaling needs, microservices architecture is a reasonable choice. Monolithic architecture is more suitable for early projects with simple business, small teams, and rapid iteration.

---

## 5. Existing Code Architecture Hierarchy View

### 5.1 Module Overview

```
game-hub-parent/
├── apps/                  ← Backend services
│   ├── gateway/           ← API & WS access layer (JWT validation, routing, session management)
│   ├── game-service/      ← Gomoku + generic countdown (room management, game logic, WebSocket)
│   ├── system-service/    ← User service (user management, permissions, PostgreSQL persistence, friend system, notification system, file storage)
│   ├── chat-service/      ← Chat service (lobby/room chat, private chat, system notification push, message persistence)
│   └── pom.xml
├── libs/                  ← Shared libraries
│   ├── session-common/    ← Session model + registry (Redis implementation)
│   ├── session-kafka-notifier/ ← Session event publishing/subscription (Kafka)
│   └── web-common/        ← Web common components (unified API response format, JWT user info extraction, Feign authentication)
├── docs/                  ← Architecture/deployment/database documentation
├── docker-compose.yml     ← Local development environment (Keycloak, PostgreSQL, Redis, Kafka)
└── pom.xml

game-hub-web/              ← Frontend application (independent project)
├── src/
│   ├── pages/             ← Page components (HomePage, LobbyPage, GameRoomPage, etc.)
│   ├── components/        ← Components (Header, GlobalChat, ProtectedRoute, etc.)
│   ├── contexts/          ← React Context (AuthContext, OngoingGameContext)
│   ├── hooks/             ← Custom Hooks (useGomokuGame, useAuth, etc.)
│   └── services/          ← Service layer (api, ws, auth)
├── package.json
└── vite.config.js
```

**Description**:
- **Backend**: Four core services (gateway, game-service, system-service, chat-service) + three shared libraries (session-common, session-kafka-notifier, web-common)
- **Frontend**: Independent React SPA application (game-hub-web), accesses backend API through Gateway, chat-service already integrated
- **Deployment**: Docker Compose supports local development, K8s deployment pending configuration

---

### 5.2 `apps/game-service` Architecture and Responsibilities

#### 5.2.1 Service Positioning and Package Structure

**Service Positioning**: Carries "rules and game execution for all board games". Exposes game domain API/WS externally; does not carry system domain capabilities like users/permissions/menus/chat.

**Package Structure** (Current Implementation):
- `games/gomoku/`: Gomoku (rule adjudication, AI, game state, WS controllers, TurnClockCoordinator)
  - `interfaces/`: Interface layer (HTTP REST, WebSocket STOMP)
    - `http/`: HTTP REST interfaces (`GomokuRestController`, `RoomListController`, `GomokuDebugController`)
    - `http/dto/`: HTTP interface DTOs (`RoomListResponse`, `RoomSummary`)
    - `ws/`: WebSocket STOMP interfaces (`GomokuWsController`, `GomokuResumeController`)
    - `ws/dto/`: WebSocket message DTOs (`GomokuMessages`, `ResumeMessages`)
  - `service/`: Service layer (core business logic)
  - `domain/`: Domain layer (models, rules, AI, repository interfaces)
  - `application/`: Application orchestration layer (countdown coordinator `TurnClockCoordinator`)
  - `infrastructure/redis/`: Redis implementation (repository implementation, key conventions)
- `clock/`: Generic countdown engine (business-agnostic `CountdownScheduler` and implementation)
- `application/`: Application layer (cross-game common services, such as user directory service `UserDirectoryService`)
- `infrastructure/`: Infrastructure layer
  - `client/`: External service clients (`system/SystemUserClient`)
  - `redis/`: Global Redis infrastructure (connection configuration, utility classes)
  - `scheduler/`: Scheduler configuration (`AiSchedulerConfig`)
- `platform/`: Platform layer
  - `config/`: Platform configuration (Spring Security, OAuth2 Resource Server)
  - `ongoing/`: Ongoing game tracking (`OngoingGameTracker`, `OngoingGameInfo`)
  - `transport/`: Transport layer utilities (`Envelope`, `MeController`, `OngoingGameController`)
  - `ws/`: WebSocket configuration and broadcast utilities

**Key Design Principles**:
- **Countdown**: As a generic engine library, reused by each game's `TurnClockCoordinator`, not independently deployed as a service.
- **AI**: Cohesive by game, each placed in `games/{game}/domain/ai`; only split out when independent scaling or specific computing power (GPU) is needed.
- **Authentication**: Strictly depends on user identity (userId/roles) passed from gateway, `game-service` executes entry/seat/move authorization by userId; `seatKey` is only used as auxiliary credential for refresh/recovery.

#### 5.2.2 Key Interaction Flows

**Enter Room**:
- Gateway → game-service, carries user identity (userId in JWT)
- game-service validates owner/member/spectator permissions (assigns seats through `resolveAndBindSide()`)
- If `seatKey` is provided, restores seat binding through `bindBySeatKey()`

**Game Start**:
- Current implementation: Players create room via HTTP, or send `startGame` message via WebSocket
- Future planning: Lobby assigns/creates room → game-service initializes board and countdown anchor
- Initialization: Create empty board (`GameStateRecord`), set room state to `WAITING`, initialize seat binding

**Move Flow**:
- WebSocket message `/app/gomoku.place` → `GomokuWsController.place()`
- Authentication: Extract `userId` from JWT, bind seat through `resolveAndBindSide()`
- Rule validation: Validate room state (must be `PLAYING`), turn (must be current player), coordinate legality, forbidden moves (RENJU rules)
- Execute move: `GomokuState.apply()` updates board
- Win/loss determination: `GomokuJudge.outcomeAfterMove()` determines if ended
- CAS save: Use `expectedStep` (piece count) and `expectedTurn` (current player) as expected values, atomically update through Redis WATCH/MULTI/EXEC
- Broadcast: Simultaneously send `STATE` (incremental update) and `SNAPSHOT` (full snapshot) events to `/topic/room.{roomId}`
- Sync countdown: `TurnClockCoordinator.syncFromState()` starts/continues countdown

**PVE AI Move**:
- After player move, if it's AI's turn, delay 1-1.5 seconds execution (using `aiScheduler` thread pool)
- Call `GomokuAI.bestMove()` to get suggestion
- Execute AI move (reuse `place()` method)
- **Note**: Current code uses hardcoded `Board.WHITE` (line 429) for AI move CAS condition, risky, should use `now.current()` as `expectedTurn`

**End Game and Records**:
- Current implementation: game-service updates score (`incrSeriesOnFinish()`), resets ready state, switches room state to `WAITING`
- Future planning: game-service generates "game end event", Rating/Record asynchronously calculates and archives

**Chat**:
- Current implementation: Frontend local chat (not connected to backend)
- Future planning: Chat manages room channels; game-service only as event source (e.g., enter/exit/end game)

#### 5.2.3 Data and Storage

**Redis Key Space Design** (Gomoku):
- **Room metadata**: `gomoku:room:{roomId}` (HASH, TTL 48 hours)
- **Game state**: `gomoku:room:{roomId}:game:{gameId}:state` (HASH, TTL 48 hours)
- **Turn anchor**: `gomoku:room:{roomId}:turn` (HASH, TTL dynamic, based on deadline)
- **Seat binding**: `gomoku:room:{roomId}:seats` (HASH, TTL 48 hours)
- **Seat key**: `gomoku:room:{roomId}:seatKey:{seatKey}` (String, TTL 48 hours)
- **User information cache**: `gomoku:room:{roomId}:users` (HASH, TTL 30 minutes)
- **Series statistics**: `gomoku:room:{roomId}:series` (HASH, TTL 48 hours)
- **Room index**: `gomoku:rooms:index` (ZSET, score is createdAt, TTL 48 hours)
- **User ongoing game**: `gomoku:user:{userId}:ongoing` (String, TTL 48 hours)

**Generic Countdown Key Space**:
- **Countdown state**: `countdown:{key}` (String, TTL 24 hours)
- **Countdown holder lock**: `countdown:holder:{key}` (String, TTL 10 seconds, SETNX)

**Serialization**:
- Current implementation: Uses JDK serialization (`RedisTemplate` default)
- Plan: Uniformly use JSON serialization (cross-version/cross-language friendly), explicitly manage TTL and cleanup

**Idempotency and Consistency**:
- **Game state update**: Uses CAS (WATCH/MULTI/EXEC) to ensure expected step count/turn player match; avoid concurrent overwrite
- **Countdown timeout**: Uses distributed holder lock (SETNX+TTL), avoid multiple nodes repeatedly executing timeout callbacks

#### 5.2.4 Consistency and Concurrency Strategy

**Game Write (CAS Mechanism)**:
- Implementation: Uses Redis WATCH/MULTI/EXEC to ensure expected step count/turn player match
- Expected values: `expectedStep` (current piece count), `expectedTurn` (current player 'X'/'O')
- Atomic operation: Simultaneously updates `GameStateRecord` and `TurnAnchor` (next turn deadline)
- Conflict handling: If EXEC returns null (transaction conflict), treat as update failure, frontend can retry

**Countdown Concurrency Control**:
- **Stop timing**: When `stop()`, delete state key (`countdown:{key}`) and holder lock (`countdown:holder:{key}`), avoid restart mis-recovery
- **Recovery scan**: Current implementation uses `redis.keys("countdown:*")` (line 134), blocking risk exists, need to switch to SCAN cursor traversal
- **Holder lock**: TTL 10 seconds, cleanup/idempotent after callback completes, avoid multiple nodes repeatedly executing timeout callbacks

**AI Execution Concurrency Control**:
- **Local debounce**: Uses `ConcurrentHashMap<String, ScheduledFuture<?>> pendingAi`, same room only keeps one pending AI task
- **Distributed mutex**: Current implementation lacks room-level distributed lock, multi-node deployment may duplicate execution, need to implement Redis SETNX (`ai:lock:{roomId}` + TTL)
- **CAS condition**: Current implementation AI move uses hardcoded `Board.WHITE` (line 429), should use `now.current()` as `expectedTurn`

#### 5.2.5 Naming and Conventions

**Redis Key Prefixes**:
- Game domain: `gomoku:` (Gomoku-related keys)
- Generic domain: `countdown:` (used by countdown engine)
- Unified management: Centralized management through `RedisKeys` class, avoid scattered strings

**WebSocket Topic Conventions**:
- Room broadcast: `/topic/room.{roomId}` (all players in room subscribe)
- Point-to-point messages:
  - `/user/queue/gomoku.seat`: Seat key (seatKey) push
  - `/user/queue/gomoku.full`: Full sync (FullSync), for refresh re-entry
  - `/user/queue/gomoku.kicked`: Kick event

**Code Comment Standards**:
- Class/method: Javadoc comments
- Code blocks: Pre-line comments explain key logic
- Inline comments: Minimal necessary comments, avoid excessive commenting

#### 5.2.6 Class Responsibility List

| Package / Class | Functional Positioning |
|----------------|------------------------|
| `GameServiceApplication` | Spring Boot startup entry, scans all game/platform components. |
| `clock.ClockAutoConfig` | Auto-configuration for countdown scheduling related Beans. |
| `clock.ClockSchedulerConfig` | Configures scheduler thread pool and task properties. |
| `clock.scheduler.CountdownScheduler` | Countdown scheduling interface definition. |
| `clock.scheduler.CountdownSchedulerImpl` | Countdown implementation, drives room timing. |
| `application.user.UserDirectoryService` | User directory service (unified entry point for game domain calling user domain, calls system-service through Feign Client). |
| `application.user.UserProfileView` | User profile view DTO. |
| `common.WebExceptionAdvice` | Global exception handler, unified HTTP response body. |
| `engine.core.AiAdvisor` | AI move advisor, coordinates Engine mode. |
| `engine.core.Command` | Engine command abstraction (move, undo, etc.). |
| `engine.core.EngineMode` | Engine running mode enumeration (PVP/PVE). |
| `engine.core.GameState` | Generic game state carrier. |
| `games.gomoku.application.TurnClockCoordinator` | Handles Gomoku turn and countdown coordination. |
| `games.gomoku.domain.ai.Evaluator` | Board evaluation calculation. |
| `games.gomoku.domain.ai.GomokuAI` | Provides AI actions based on evaluation strategy. |
| `games.gomoku.domain.dto.AiIntent` | AI request/response DTO. |
| `games.gomoku.domain.dto.GameStateRecord` | Data object for saving/restoring game state. |
| `games.gomoku.domain.dto.RoomMeta` | Room metadata (configuration, mode, both sides). |
| `games.gomoku.domain.dto.RoomMetaConverter` | Conversion between DTO and entity/cache. |
| `games.gomoku.domain.dto.SeatsBinding` | User and seat binding relationship. |
| `games.gomoku.domain.dto.TurnAnchor` | Records current turn anchor. |
| `games.gomoku.domain.enums.Mode` | Game mode (PVP/PVE). |
| `games.gomoku.domain.enums.RoomPhase` | Room phase (waiting, playing, settlement). |
| `games.gomoku.domain.enums.Rule` | Rule set (free, forbidden moves, etc.). |
| `games.gomoku.domain.model.Board` | Board data structure. |
| `games.gomoku.domain.model.Game` | Aggregate root, manages entire game state. |
| `games.gomoku.domain.model.GomokuSnapshot` | Game snapshot. |
| `games.gomoku.domain.model.GomokuState` | Current game state object. |
| `games.gomoku.domain.model.Move` | Single move record. |
| `games.gomoku.domain.model.Room` | Gomoku room entity. |
| `games.gomoku.domain.model.RoomView` | Room view (for list display). |
| `games.gomoku.domain.model.SeriesView` | Series detection view. |
| `games.gomoku.domain.repository.AiIntentRepository` | AI intent storage interface. |
| `games.gomoku.domain.repository.GameStateRepository` | Game state storage interface (supports CAS). |
| `games.gomoku.domain.repository.RoomRepository` | Room persistence interface. |
| `games.gomoku.domain.repository.TurnRepository` | Turn data interface. |
| `games.gomoku.domain.rule.GomokuJudge` | Win/loss adjudicator (standard rules). |
| `games.gomoku.domain.rule.GomokuJudgeRenju` | Renju rule adjudication implementation. |
| `games.gomoku.domain.rule.Outcome` | Game result enumeration. |
| `games.gomoku.infrastructure.redis.RedisKeys` | Redis Key convention collection (unified key prefix management). |
| `games.gomoku.infrastructure.redis.repo.RedisAiIntentRepository` | AI intent Redis implementation. |
| `games.gomoku.infrastructure.redis.repo.RedisGameStateRepository` | Game state Redis implementation (supports CAS). |
| `games.gomoku.infrastructure.redis.repo.RedisRoomRepository` | Room Redis implementation. |
| `games.gomoku.infrastructure.redis.repo.RedisTurnRepository` | Turn Redis implementation. |
| `games.gomoku.interfaces.http.GomokuDebugController` | Debug interface (for development). |
| `games.gomoku.interfaces.http.GomokuRestController` | HTTP interface (create room, sync state, etc.). |
| `games.gomoku.interfaces.http.RoomListController` | Room list interface (query room list). |
| `games.gomoku.interfaces.ws.dto.GomokuMessages` | WS message carrier (game broadcast). |
| `games.gomoku.interfaces.ws.dto.ResumeMessages` | Reconnection message body. |
| `games.gomoku.interfaces.ws.GomokuResumeController` | STOMP endpoint responsible for reconnection flow. |
| `games.gomoku.interfaces.ws.GomokuWsController` | Main WS/STOMP controller (move, room events). |
| `games.gomoku.service.GomokuService` | Service interface. |
| `games.gomoku.service.impl.GomokuServiceImpl` | Gomoku domain service implementation. |
| `infrastructure.client.system.SystemUserClient` | System service client (Feign Client, calls system-service to get user information). |
| `infrastructure.redis.RedisConfig` | Redis connection configuration. |
| `infrastructure.redis.RedisOps` | Redis operation encapsulation (CAS, etc.). |
| `infrastructure.scheduler.AiSchedulerConfig` | AI task scheduler configuration. |
| `platform.config.SecurityConfig` | Spring Security / OAuth2 resource server configuration. |
| `platform.ongoing.OngoingGameInfo` | Ongoing game information DTO. |
| `platform.ongoing.OngoingGameTracker` | Ongoing game tracker (records/queries user's ongoing games). |
| `platform.transport.Envelope` | WebSocket message packaging utility. |
| `platform.transport.MeController` | "/me" self-check interface, returns current user information. |
| `platform.transport.OngoingGameController` | Ongoing game interface (queries user's ongoing games). |
| `platform.ws.SessionInvalidatedListener` | Listens to session invalidation events, notifies WS. |
| `platform.ws.WebSocketAuthChannelInterceptor` | STOMP authentication interceptor, injects user information. |
| `platform.ws.WebSocketDisconnectHelper` | Handles connection disconnection, cleans up state. |
| `platform.ws.WebSocketSessionManager` | Manages user and WS Session mapping. |
| `platform.ws.WebSocketStompConfig` | Registers STOMP endpoints and message broker. |

---

### 5.3 `apps/gateway` Class Responsibilities

| Class | Functional Positioning |
|-------|------------------------|
| `GatewayApplication` | Access layer startup entry. |
| `config.SecurityConfig` | Spring Security, OAuth2 Resource Server settings. |
| `config.JwtDecoderConfig` | Configures JWT decoding and public key caching. |
| `config.OAuth2ClientConfig` | Manages Client credentials when calling Keycloak. |
| `config.KeycloakAdminProperties` | Reads Keycloak admin configuration. |
| `config.KeycloakAdminConfig` | Builds Keycloak Admin Client Bean. |
| `controller.TokenController` | Exposes Token generation/refresh and other auxiliary interfaces. |
| `controller.SessionStateVerificationController` | Provides session state validation, mutual kick and other interfaces. |
| `filter.WebSocketTokenFilter` | Validates JWT and injects Header during WS handshake. |
| `handler.LoginSessionKickHandler` | Receives admin "kick" commands and dispatches. |
| `service.JwtBlacklistService` | Maintains revoked Token list. |
| `service.KeycloakSsoLogoutService` | Calls Keycloak for SSO logout. |

---

### 5.4 `apps/system-service` Class Responsibilities

| Class / Package | Functional Positioning |
|----------------|------------------------|
| `SystemServiceApplication` | Backend service startup entry. |
| `common.Result` | Unified API response encapsulation. |
| `config.KeycloakConfig` | Keycloak Admin Client configuration. |
| `config.RestTemplateConfig` | Provides authenticated `RestTemplate`. |
| `config.SecurityConfig` | Admin interface authentication (Spring Security). |
| `controller.AuthController` | Login, refresh Token, guest registration. |
| `controller.internal.KeycloakEventController` | Accepts Keycloak event callbacks. |
| `controller.SessionMonitorController` | Session monitoring controller (for development and debugging, view online user list). |
| `controller.user.UserController` | User CRUD, state management. |
| `dto.keycloak.KeycloakEventPayload` | Keycloak event carrier. |
| `dto.request.CreateUserRequest` | Create user request body. |
| `dto.request.LoginRequest` | Login request body. |
| `dto.request.UpdateUserRequest` | Update user request body. |
| `dto.response.TokenResponse` | Login/refresh response body. |
| `dto.response.UserInfo` | User information response DTO. |
| `dto.response.UserSessionSnapshotWithUserInfo` | Session snapshot with user information (for session monitoring). |
| `entity.permission.SysPermission` | System permission entity. |
| `entity.role.SysRole` | Role entity. |
| `entity.role.SysRolePermission` | Role-permission association. |
| `entity.role.SysRolePermissionId` | Composite primary key. |
| `entity.role.SysUserRole` | User-role association. |
| `entity.role.SysUserRoleId` | Composite primary key. |
| `entity.user.SysUser` | System user entity. |
| `entity.user.SysUserProfile` | User extended profile. |
| `exception.BusinessException` | Business exception definition. |
| `exception.GlobalExceptionHandler` | Global exception interceptor. |
| `repository.permission.SysPermissionRepository` | Permission repository. |
| `repository.role.SysRoleRepository` | Role repository. |
| `repository.role.SysRolePermissionRepository` | Role-permission repository. |
| `repository.role.SysUserRoleRepository` | User-role repository. |
| `repository.user.SysUserRepository` | User repository. |
| `repository.user.SysUserProfileRepository` | User profile repository. |
| `service.keycloak.KeycloakEventService` | Interface for processing events from Keycloak. |
| `service.keycloak.impl.KeycloakEventServiceImpl` | Event implementation, stores to database/triggers business. |
| `service.user.UserService` | User use case interface. |
| `service.user.impl.UserServiceImpl` | User service implementation. |

(`controller.role`, `service.role`, `service.security` packages are currently empty, reserved for expansion.)

---

### 5.5 `apps/chat-service` Class Responsibilities

| Class / Package | Functional Positioning |
|----------------|------------------------|
| `ChatServiceApplication` | Chat service startup entry. |
| `config.SecurityConfig` | Spring Security / OAuth2 resource server configuration. |
| `config.WebSocketStompConfig` | WebSocket STOMP configuration (message broker, endpoint registration). |
| `controller.http.ChatRestController` | HTTP REST interface (send message, query history, etc.). |
| `controller.http.ChatHistoryController` | Chat history query interface (lobby, room, private chat history). |
| `controller.http.ChatSessionController` | Session management interface (create/query session, mark read, etc.). |
| `controller.http.NotificationInternalController` | Internal notification push interface (for system-service to call). |
| `controller.ws.ChatWsController` | WebSocket STOMP controller (message send/receive, subscription management). |
| `entity.ChatMessage` | Chat message entity (PostgreSQL persistence). |
| `entity.ChatSession` | Chat session entity (lobby, room, private chat sessions). |
| `entity.ChatSessionMember` | Session member entity (association between session and user). |
| `infrastructure.client.SystemUserClient` | System service client (Feign Client, calls system-service to get user information). |
| `repository.ChatMessageRepository` | Message repository interface. |
| `repository.ChatSessionRepository` | Session repository interface. |
| `repository.ChatSessionMemberRepository` | Session member repository interface. |
| `service.ChatMessagingService` | Message send/receive service interface. |
| `service.impl.ChatMessagingServiceImpl` | Message send/receive service implementation (message validation, broadcast, persistence). |
| `service.ChatSessionService` | Session management service interface. |
| `service.impl.ChatSessionServiceImpl` | Session management service implementation (create/query session, member management). |
| `service.ChatHistoryService` | Chat history service interface. |
| `service.impl.ChatHistoryServiceImpl` | Chat history service implementation (Redis cache + PostgreSQL persistence). |
| `service.NotificationPushService` | System notification push service interface. |
| `service.impl.NotificationPushService` | System notification push service implementation (pushes notifications to frontend via WebSocket). |
| `service.UserProfileCacheService` | User information cache service (Redis cache, shared across services). |
| `ws.WebSocketAuthChannelInterceptor` | WebSocket authentication interceptor (validates JWT Token, injects user information). |
| `ws.WebSocketSessionManager` | WebSocket Session manager (user and Session mapping). |
| `ws.WebSocketDisconnectHelper` | WebSocket disconnection handling (cleans up subscriptions, notifies other users). |
| `ws.SessionInvalidatedListener` | Session invalidation listener (listens to login/logout events, cleans up WebSocket connections). |

---

### 5.6 `libs/session-common`

| Class | Functional Positioning |
|-------|------------------------|
| `SessionCommonAutoConfiguration` | Starter auto-configuration entry. |
| `SessionRedisConfig` | RedisTemplate & serialization configuration. |
| `SessionRegistry` | Unified session registry, records online status. |
| `event.SessionInvalidatedEvent` | Session invalidation event definition. |
| `model.LoginSessionInfo` | Login state information. |
| `model.SessionStatus` | Session status enumeration. |
| `model.UserSessionSnapshot` | User online snapshot. |
| `model.WebSocketSessionInfo` | WS Session description. |

### 5.7 `libs/session-kafka-notifier`

| Class | Functional Positioning |
|-------|------------------------|
| `config.SessionKafkaConfig` | Kafka Topic/Producer/Consumer configuration. |
| `config.SessionKafkaNotifierAutoConfiguration` | Starter auto-configuration. |
| `listener.SessionEventConsumer` | Consumes Kafka session events. |
| `listener.SessionEventListener` | Listens to Spring events and forwards to Kafka. |
| `publisher.SessionEventPublisher` | Encapsulates event publishing. |

### 5.8 `libs/web-common`

| Class | Functional Positioning |
|-------|------------------------|
| `ApiResponse` | Unified API response format (record type, supports success/error/badRequest/unauthorized/forbidden/notFound/conflict/serverError). |
| `CurrentUserHelper` | Current user information extraction utility class (extracts userId, username, nickname, email, roles, etc. from JWT token). |
| `CurrentUserInfo` | Current user information DTO (record type, contains userId, username, nickname, email, realmRoles, clientRoles). |
| `feign.FeignAuthAutoConfiguration` | Feign client authentication auto-configuration (automatically extracts JWT Token from current request and adds to Feign request Header). |

**Usage Scenarios**:
- `ApiResponse`: All HTTP interfaces of game-service and system-service uniformly use this format for responses
- `CurrentUserHelper`: Extracts user information from `@AuthenticationPrincipal Jwt jwt` parameter, avoids duplicate code
- `FeignAuthAutoConfiguration`: Automatically passes JWT Token when game-service calls system-service

### 5.7 Backend Technology Roadmap (Microservices and Infrastructure)

- **Inter-Service Synchronous Communication**
  - **Protocol**: HTTP/JSON (REST style), unified access authentication and routing by `gateway`.
  - **Client**: Prefer **Spring Cloud OpenFeign** or WebClient (currently has `RestTemplate`, can smoothly migrate to Feign later).
  - **Calling Methods**:
    - `game-service` → `system-service`: Calls `/api/users/**` and other interfaces through Feign Client / HTTP Client to get `UserInfo`, manage admin users.
    - `system-service` → `chat-service`: Calls `/api/internal/notify` interface through Feign Client to push system notifications.
    - Call addresses injected through configuration items, e.g., `system-service.url`, `chat-service.url`, supports three environment switches: local (`http://localhost:8082`, `http://localhost:8083`), Docker Compose (`http://system-service:8082`, `http://chat-service:8083`), K8s (`http://system-service`, `http://chat-service`).

- **Service Discovery and Deployment Form**
  - **No Nacos / Eureka** as registry, service discovery handled by runtime platform:
    - Local development: Each service runs on different ports in IDE (`localhost:8080/8081/8082`), specify mutual call addresses through configuration.
    - Docker Compose: Uses container built-in DNS (Service Name), e.g., `http://game-service:8081`, `http://system-service:8082`.
    - Kubernetes: Uses **K8s Service + DNS**, services call each other through `http://game-service`, `http://system-service` and other Service names.
  - This way code layer only depends on environment configuration, not bound to any specific registry, achieving more cloud-native form.

- **Circuit Breaker / Fallback / Retry**
  - Compared to domestic Sentinel, here chooses **Resilience4j** as core fault tolerance library.
  - Integration method: Through **Spring Cloud Circuit Breaker + Resilience4j**, use annotations (e.g., `@CircuitBreaker`, `@Retry`) on Feign Client or Service methods to implement:
    - Fast failure + friendly fallback (return fallback user information / mark as "service busy") when downstream service unavailable.
    - Enable limited retries for some idempotent read operations to improve stability.
  - Circuit breaker strategies and thresholds centrally managed in configuration, convenient for production tuning.

- **Configuration Center and Dynamic Refresh**
  - **12-Factor Principle**: Follows 12-Factor App concept, configuration injected through environment variables, not hardcoded in code.
  - **Configuration Management Solution**:
    - **Development/Test Environment**: Uses **Spring Cloud Config Server + Git repository** as centralized configuration source, all services' `application-*.yml` uniformly stored in configuration repository, organized by `application name + profile`.
    - **Production Environment (K8s)**: Uses **K8s ConfigMap/Secret** to manage configuration, sensitive information like secrets stored in Secret, ordinary configuration stored in ConfigMap.
    - **Secret Management (Future)**: Integrate **Vault** or use K8s Secret, replace Nacos for storing sensitive configuration, improve security.
  - **Dynamic Refresh**: Through **Spring Cloud Bus + Kafka** to implement configuration change broadcast:
    - After updating Git configuration and refreshing Config Server, send Bus event, trigger each service `/actuator/bus-refresh`.
    - Beans marked with `@RefreshScope` (e.g., downstream service URLs, switches, rate limiting configuration, etc.) can hot update without downtime.

- **Asynchronous Events and Decoupling**
  - Message middleware: **Kafka** already serves as unified event bus (Session events, future game events, point changes, etc.).
  - Design principles:
    - Synchronous calls for "strong consistency + immediate return" scenarios (create room, query user profile).
    - Asynchronous events for "delayable post-processing logic" (write records after game ends, update points/level, send notifications).
  - Through `session-kafka-notifier` and other Starters, convert session/game events to Kafka messages for subsequent statistics/BI services to subscribe.

- **Security and Authentication**
  - Uniformly uses **Keycloak + Spring Security OAuth2 Resource Server**:
    - `gateway` validates JWT, injects user context, provides unified protection for downstream services.
    - Backend services (`game-service`, `system-service`) as Resource Servers, parse JWT / Token Relay from Gateway.

- **Object Storage**
  - **MinIO (S3 Compatible)**: Used to store avatars, game replay files, activity materials and other files.
  - **Advantage**: S3 protocol universal, can smoothly migrate to AWS S3, Alibaba Cloud OSS and other cloud storage services.
  - **Current Status**: ✅ Implemented, system-service has integrated MinIO, supports avatar upload, game replay upload, activity material upload.

- **CI/CD**
  - **GitHub Actions**: Cloud-native build and deployment, demonstrates international engineering capabilities.
  - **Process**: Code commit → Automatically build Docker image → Push to Registry → Deploy to environment (Docker Compose / K8s).
  - **Current Status**: ❌ Not implemented, planned.

- **Overall Style and Selection Philosophy**
  - **Core Technology Stack**: **Spring Boot + Spring Cloud Gateway + OpenFeign + Resilience4j + Spring Cloud Config + Kafka + Keycloak + PostgreSQL + Redis + K8s**.
  - **Selection Principles**:
    - 🧩 **Standards First**: Follows international standards (OAuth2, OIDC, OpenTelemetry, K8s, S3), convenient for aligning expectations in overseas interview scenarios.
    - 🚫 **Decentralized Dependencies**: Does not depend on Nacos / Apollo / Sentinel and other centralized components, uses K8s Service DNS + ConfigMap/Secret + event bus.
    - 🔄 **Replaceable**: Keycloak can be replaced with Sa-Token / Spring Auth Server; Gateway can be replaced with Kong/Envoy; Config can be replaced with Nacos (but prefers K8s ConfigMap).
    - 🌍 **Cloud-Native Compatible**: All components can run under Kubernetes, Docker Compose.
    - 💡 **Business Independent**: game-service core logic minimizes infrastructure dependencies, maintains pure logic and reusability.

---

## 6. Next Improvement Plan

> Note: This chapter only lists technical improvement items to be completed. For completed features, please refer to "3.1 Currently Completed Features" chapter.

### 6.1 game-service Improvement Items

1. **Fixes and Hardening (P0)**:
   - ❌ **Countdown recovery switch to SCAN**: Currently uses `redis.keys()` (`CountdownSchedulerImpl.restoreAllActive()` line 134), blocking risk exists, need to switch to SCAN cursor traversal
   - ❌ **Holder lock TTL configurable**: Currently hardcoded 10 seconds (`CountdownSchedulerImpl.tryAcquireHolder()` line 236), need to externalize configuration
   - ❌ **AI execution distributed mutex**: Currently only local debounce (`pendingAi` ConcurrentHashMap), lacks room-level distributed lock, multi-node deployment may duplicate execution, need to implement Redis SETNX (`ai:lock:{roomId}` + TTL)
   - ❌ **AI CAS expectedTurn correction**: Currently hardcoded `Board.WHITE` (`GomokuWsController.runAiTurn()` line 429), should use `now.current()` as expectedTurn

2. **Abstract Generic Room Module (P1)**:
   - owner/members/spectators + permission points
   - Clear interface with Lobby (currently Lobby functionality cohesive in game-service, can be split in future)

3. **Event-Driven (P1)**:
   - Game start/end events → Rating/Record, Chat, notifications, etc.
   - Current implementation: game-service directly updates score, future change to publish events

4. **New Game Integration (P2)**:
   - Reuse rules, AI, Coordinator and generic countdown by package structure
   - Create new game directory under `games/` (e.g., `games/chess/`), organize code with same structure

### 6.2 Platform-Level Improvement Items

1. **Link Acceptance (P0)**:
   - **Goal**: Complete deployment/configuration documentation and automation scripts, ensure new members can reproduce with one-click startup
   - **Current Status**: ✅ Keycloak→Gateway→game-service→Session event link connected, but lacks complete deployment documentation

2. **Chat Room MVP (P0)**:
   - **Goal**: Abstract message model, implement lobby chat & room chat (including message persistence, mute, WS broadcast); future channels/private chat expand on this basis
   - **Current Status**: ✅ Implemented, chat-service supports lobby/room chat, private chat, system notification push, frontend integrated

3. **Admin Governance (P1)**:
   - **Goal**: Complete menu/permission configuration interface, audit logs, provide minimum closed loop for operations
   - **Current Status**: ✅ Session monitoring API implemented (`/internal/sessions`), ✅ Kick functionality implemented, ❌ Missing menu/permission configuration interface

4. **Service Split (P1)**:
   - **Goal**: Lobby/Engine separation, and precipitate `game-engine-template` (room, matching, settlement interface conventions) for rapid incubation of new mini-games
   - **Current Status**: Lobby functionality cohesive in game-service, matching functionality not implemented

5. **Data Persistence (P1)**:
   - **Goal**: Write game records and chat history to PostgreSQL/MongoDB, expose records/leaderboards/replays, provide data foundation for player profiles and growth system
   - **Current Status**: ✅ system-service user data persisted to PostgreSQL, ✅ chat-service chat history persisted to PostgreSQL, ✅ system-service implemented MinIO file storage (avatars, game replays, activity materials), ❌ Game records not persisted

6. **Observability and Deployment (P1)**:
   - **Goal**: Docker Compose → K8s, multi-environment configuration governance, Prometheus/Grafana + OpenTelemetry + Jaeger, form unified monitoring and alerting
   - **Observability System**:
     - **Metrics Monitoring (Prometheus)**: Business metrics (`active_rooms` active room count, `active_clocks` running countdown count, `timeouts_total` timeout loss count, `reconnects_total` reconnection count, `ai_think_time_avg` AI average thinking time, `ws_connections_active` WebSocket connection count) and system metrics (CPU, memory, GC, etc.).
     - **Link Tracing (Jaeger)**: Full-link Trace, tracks complete path of requests from Gateway → game-service → Redis.
     - **Log Aggregation (Loki + Grafana)**: Unified log format, convenient for troubleshooting and analysis.
     - **Visualization (Grafana)**: Metrics Dashboard, alert rules, business reports.
   - **Current Status**: ✅ Docker Compose configured, ❌ K8s not configured, ❌ Prometheus/Grafana not integrated, ❌ OpenTelemetry/Jaeger not integrated

7. **Extended Services (P2)**:
   - **Goal**: Chat advanced (channels, private chat, teams), Matchmaking, Rating, Analytics and multi-game operations tools, support activities, tournaments and BI
   - **Current Status**: ❌ Not implemented

---

## 7. Solo Iteration Roadmap (Demo First)

> Goal: First deliver a "demonstrable" version (login→lobby→Gomoku battle→basic admin), then gradually expand, avoid spreading everything at once and getting overwhelmed.

### Phase A: Demonstrable Version (1 person · 2~3 weeks)
- **Connect Link**: ✅ Completed Keycloak configuration, Gateway/game service startup scripts, `docker-compose up` can access lobby and complete one game of Gomoku.
- **Minimal Lobby/Game Experience**: ✅ Completed
  - ✅ Lobby supports creating rooms (PVP/PVE), room list, joining rooms, entering by room ID
  - ✅ Game page: 15x15 board, real-time moves, countdown, ready/start game, resign, leave room
- **Minimal Admin**: ✅ Partially completed
  - ✅ Session monitoring API (`/internal/sessions`): View online user list
  - ✅ Kick functionality: Gateway provides kick interface, supports forced logout
  - ❌ Admin management interface: Only frontend session monitoring page (SessionMonitorPage), missing menu/permission configuration interface
- **Demo Material**: ✅ Can demonstrate complete flow (login→create room→join→battle→admin kick)

### Phase B: Social Support (1 person · 3~4 weeks)
- **Chat Room MVP**: ✅ Implemented
  - **Current Status**: chat-service implemented, supports lobby/room chat, private chat, system notification push, frontend integrated
  - **Implemented Features**: Public lobby chat, room chat, private messages, message persistence (Redis + PostgreSQL), system notification push
  - **Next Steps**: Improve mute/block functionality, sensitive word filtering, send rate limiting
- **Player Center Prototype**: ❌ Not implemented
  - **Current Status**: ProfilePage is placeholder page
  - **Next Steps**: `/me` displays records/recent rooms (first write Mock/Redis statistics), provide entry for future growth system

### Phase C: Platform Evolution (Schedule as needed)
- **Lobby/Engine Split**: ❌ Not implemented
  - **Current Status**: Lobby functionality cohesive in game-service, matching functionality not implemented
  - **Next Steps**: Extract matching/room service, produce `game-engine-template`, plant hooks for second mini-game
- **Records & Data**: ❌ Not implemented
  - **Current Status**: Records only exist in Redis, not persisted
  - **Next Steps**: Add PostgreSQL schema, implement game records, leaderboard API
- **Observability/Deployment**: ❌ Not implemented
  - **Current Status**: Only Docker Compose, K8s not configured, Prometheus/Grafana not integrated
  - **Next Steps**: Add Prometheus/Grafana, improve CI/CD (GitHub Actions + Docker Registry), then consider more games, channel chat, operations tools

> If time is tight, can showcase externally after Phase A ends, then pull items from Phase B/C by priority, ensuring each step has deliverable results.

> Through the above blueprint and current state mapping, can ensure "system-level framework" comes first, social and game capabilities evolve under unified platform, avoiding duplicate wheel-building and large-scale refactoring later.

