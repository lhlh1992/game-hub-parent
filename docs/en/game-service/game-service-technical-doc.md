# Game Service Comprehensive Technical Documentation

**Scope**:
- This document focuses on the business logic and code implementation of **game-service**.
- It includes the game business logic of the frontend (game-hub-web), but does not cover auth/authorization/SSO (see SSO doc).

## Table of Contents
1. [Service Overview](#service-overview)
2. [Tech Stack](#tech-stack)
3. [System Architecture](#system-architecture)
4. [Business Logic Details](#business-logic-details)
5. [Code Architecture Analysis](#code-architecture-analysis)
6. [Frontend/Backend Interaction Flows](#frontendbackend-interaction-flows)
7. [Data Model and Storage](#data-model-and-storage)
8. [Key Technical Implementations](#key-technical-implementations)
9. [Code Details Analysis](#code-details-analysis)
10. [Frontend Detailed Analysis](#frontend-detailed-analysis)
11. [Future Improvement Plan](#future-improvement-plan)

---

## Service Overview

### 1.1 Service Positioning
**game-service** is a generic game service responsible for game rules, match execution, and room management.

**Current implementation**: Gomoku, supporting:
- **PVP mode**: player vs. player
- **PVE mode**: player vs. AI
- **Room system**: create/join/leave rooms
- **Real-time matches**: WebSocket real-time communication
- **Countdown system**: turn-based countdown
- **Series**: multiple games per match, score tracking

**Extensibility**: modular design, organized by game type under `games/`. Future games (e.g., Chinese Checkers, Chess) can be added under `games/`, each implementing its own rules, AI, state management, etc.

**Note**: This doc mainly describes the general architecture of game-service and the current Gomoku implementation, not other services.

### 1.2 Related Services
- **game-service**: game service (rules, match execution, room management; current Gomoku; extensible) **← this doc**
- **game-hub-web**: frontend (React + Vite)
- **gateway**: API gateway (routing, gateway features)
- **system-service**: user service (user info/profile)

---

## Tech Stack

### 2.1 Backend (game-service)
- **Framework**: Spring Boot 3.x
- **Language**: Java 21
- **Web**: Spring Web + Spring WebSocket (STOMP)
- **Security**: Spring Security + OAuth2 Resource Server (JWT)
- **Storage**: Redis (room state, game state, seat binding)
- **Service Calls**: OpenFeign (call system-service)
- **Load Balancing**: Spring Cloud LoadBalancer
- **Session Mgmt**: session-common (WebSocket session management)
- **MQ**: Kafka (session invalidation notification)

### 2.2 Frontend (game-hub-web)
- **Framework**: React 19.2
- **Build**: Vite 7.2
- **Routing**: React Router 7.9
- **WebSocket**: SockJS 1.6.1 + @stomp/stompjs 7.2.1
- **State Mgmt**: React Context + Hooks
- **HTTP Client**: native fetch (wrapped as apiClient)
- **Styles**: CSS modules (global.css, header.css, game.css, etc.)

---

## System Architecture

### 3.1 Overall Diagram
```
┌─────────────────────────────────────────────────────────────┐
│                        Frontend                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  HomePage    │  │  LobbyPage   │  │ GameRoomPage │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│         │                  │                  │             │
│         └──────────────────┼──────────────────┘             │
│                            │                                │
└────────────────────────────┼────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │    Gateway      │
                    │   (routing)     │
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐
│ game-service   │  │ system-service  │  │     Kafka       │
│                │  │                 │  │ (session notif) │
│ - room mgmt    │  │ - user info     │  └─────────────────┘
│ - game logic   │  └─────────────────┘
│ - WebSocket    │
│ - Redis        │
└───────┬────────┘
        │
┌───────▼───────┐
│    Redis      │
│ - room meta   │
│ - game state  │
│ - seat bind   │
│ - turn anchor │
└──────────────┘
```

### 3.2 Backend Layering
```
game-service/
├── GameServiceApplication.java                     # Spring Boot bootstrap (@SpringBootApplication, @EnableFeignClients)
├── games/              # game modules (extensible)
│   └── gomoku/         # Gomoku module (current)
│       ├── interfaces/ # interface layer
│       │   ├── http/   # REST controllers
│       │   │   ├── GomokuRestController.java      # HTTP: create room, get view, join/leave
│       │   │   ├── RoomListController.java        # room list (lobby, paging)
│       │   │   ├── GomokuDebugController.java     # debug endpoints
│       │   │   └── dto/                           # HTTP DTO
│       │   │       ├── RoomListResponse.java      # list resp (items, nextCursor, hasMore)
│       │   │       └── RoomSummary.java           # room summary for list
│       │   └── ws/    # WebSocket controllers
│       │       ├── GomokuWsController.java        # WS messages (place, resign, ready, start, restart, kick)
│       │       ├── GomokuResumeController.java    # WS resume (refresh rejoin, return FullSync)
│       │       └── dto/                           # WS DTO
│       │           ├── GomokuMessages.java        # WS messages (PlaceCmd, BroadcastEvent, etc.)
│       │           └── ResumeMessages.java        # resume handshake & full sync DTO (ResumeCmd, FullSync)
│       ├── service/   # service layer
│       │   ├── GomokuService.java                 # Gomoku service interface (core methods)
│       │   └── impl/
│       │       └── GomokuServiceImpl.java         # core logic (room mgmt, game state, seats, etc.)
│       ├── domain/    # domain layer
│       │   ├── model/ # domain models
│       │   │   ├── Room.java                      # room entity (Series, seats, seatKey map)
│       │   │   ├── Game.java                      # single game entity (gameId, index, state, pendingAi)
│       │   │   ├── GomokuState.java               # game state (board, side to move, winner, last move)
│       │   │   ├── Board.java                     # board (15x15 grid)
│       │   │   ├── Move.java                      # a move (coord + piece, record type)
│       │   │   ├── GomokuSnapshot.java            # room snapshot (FullSync)
│       │   │   ├── RoomView.java                  # aggregated room view (meta/seats/game/anchor)
│       │   │   └── SeriesView.java                # series view (score, index, current game ID)
│       │   ├── dto/   # DTOs
│       │   │   ├── RoomMeta.java                  # room meta (mode, rule, owner, series stats)
│       │   │   ├── RoomMetaConverter.java         # converter
│       │   │   ├── SeatsBinding.java              # seat binding (X/O seats, sessionId map, ready)
│       │   │   ├── GameStateRecord.java           # game state record (Redis persisted)
│       │   │   ├── TurnAnchor.java                # turn timer anchor (side, deadline, turnSeq)
│       │   │   └── AiIntent.java                  # AI intent (for node restart recovery, scheduledAtMs)
│       │   ├── enums/ # enums
│       │   │   ├── Mode.java                      # PVP/PVE
│       │   │   ├── Rule.java                      # STANDARD/RENJU
│       │   │   └── RoomPhase.java                 # WAITING/PLAYING/ENDED
│       │   ├── rule/  # rule checking
│       │   │   ├── GomokuJudge.java               # standard rule (win/draw/validity)
│       │   │   ├── GomokuJudgeRenju.java          # renju forbidden moves
│       │   │   └── Outcome.java                   # WIN/DRAW/ONGOING
│       │   ├── ai/    # AI
│       │   │   ├── GomokuAI.java                  # AI main (threat-first + alpha-beta + forbidden filter)
│       │   │   └── Evaluator.java                 # heuristic evaluator (line length scoring)
│       │   └── repository/ # repositories
│       │       ├── RoomRepository.java            # room repo (meta, seats, seatKey)
│       │       ├── GameStateRepository.java       # game state repo (CAS)
│       │       ├── TurnRepository.java            # turn anchor repo
│       │       └── AiIntentRepository.java        # AI intent repo (restart recovery)
│       ├── application/ # application coordination
│       │   └── TurnClockCoordinator.java          # countdown coordinator (engine + business)
│       └── infrastructure/ # infra
│           └── redis/
│               ├── RedisKeys.java                 # Redis key mgmt
│               └── repo/
│                   ├── RedisRoomRepository.java       # room repo impl
│                   ├── RedisGameStateRepository.java  # game state repo impl (CAS)
│                   ├── RedisTurnRepository.java       # turn anchor repo impl
│                   └── RedisAiIntentRepository.java   # AI intent repo impl
│
├── platform/           # platform layer (shared infra)
│   ├── config/
│   │   └── SecurityConfig.java                    # Spring Security (JWT resource server, URL rules)
│   ├── ws/             # WebSocket infra
│   │   ├── WebSocketSessionManager.java           # WS session mgmt (register, disconnect, kick)
│   │   ├── WebSocketStompConfig.java              # STOMP config (message converters, interceptors)
│   │   ├── WebSocketDisconnectHelper.java         # disconnect helper
│   │   ├── WebSocketAuthChannelInterceptor.java   # WS auth interceptor (JWT)
│   │   └── SessionInvalidatedListener.java        # session invalidated listener (Kafka)
│   ├── ongoing/        # ongoing game tracking
│   │   ├── OngoingGameTracker.java                # track/query current game (Redis)
│   │   └── OngoingGameInfo.java                   # info (gameType, roomId, title, updatedAt)
│   └── transport/      # HTTP controllers
│       ├── OngoingGameController.java             # ongoing game query/cleanup (/api/ongoing-game)
│       ├── MeController.java                      # current user info (/me, from JWT)
│       └── Envelope.java                          # response wrapper
│
├── application/        # application layer (cross-game)
│   └── user/
│       ├── UserDirectoryService.java              # user directory (call system-service with circuit breaker)
│       └── UserProfileView.java                   # user profile DTO
│
├── clock/              # countdown engine (generic)
│   ├── ClockAutoConfig.java                       # auto config
│   ├── ClockSchedulerConfig.java                  # scheduler config (thread pool)
│   └── scheduler/
│       ├── CountdownScheduler.java                # countdown scheduler interface
│       └── CountdownSchedulerImpl.java            # impl (Redis persist, TICK/timeout callbacks)
│
├── engine/             # game engine core (generic)
│   └── core/
│       ├── GameState.java                         # game state interface (copy for AI search)
│       ├── AiAdvisor.java                         # AI advice interface
│       ├── Command.java                           # command interface
│       └── EngineMode.java                        # engine mode enum
│
├── common/             # common components
│   └── WebExceptionAdvice.java                     # global exception handler
│
└── infrastructure/     # global infra
    ├── client/         # Feign client
    │   └── system/
    │       └── SystemUserClient.java              # system-service Feign client (user info)
    ├── redis/          # Redis infra
    │   ├── RedisConfig.java                       # Redis config (pool, serializers)
    │   └── RedisOps.java                          # Redis ops helper
    ├── scheduler/      # schedulers
    │   └── AiSchedulerConfig.java                  # AI scheduler (PVE AI delay pool)
    └── config/         # other configs
```

**Architecture notes**:
- `games/`: organized by game type; each game implements its own rules/AI/state.
- `platform/`: shared WebSocket/session infra for all games.
- `infrastructure/`: global infra (Feign, configs).
- **Extending**: add new game under `games/{gameName}/` following same structure.

### 3.3 Frontend Architecture
```
game-hub-web/src/
├── pages/
│   ├── HomePage.jsx            # home (lobby entry, ongoing prompt)
│   ├── LobbyPage.jsx           # lobby (create/join/list rooms)
│   ├── GameRoomPage.jsx        # game room (Gomoku UI)
│   ├── ProfilePage.jsx         # profile (placeholder)
│   ├── SessionMonitorPage.jsx  # session monitor (debug)
│   └── NotFoundPage.jsx        # 404
│
├── components/
│   ├── layout/
│   │   ├── AppLayout.jsx       # layout (Header + Outlet + GlobalChat)
│   │   └── Header.jsx          # top nav (user info)
│   ├── chat/
│   │   └── GlobalChat.jsx      # global chat (collapsible)
│   └── common/
│
├── hooks/
│   ├── useGomokuGame.js        # core Gomoku logic hook
│   └── useOngoingGame.js       # ongoing game from context
│
├── contexts/
│   └── OngoingGameContext.jsx  # ongoing game context (query/refresh/end)
│
├── services/
│   ├── api/
│   │   ├── apiClient.js        # HTTP client
│   │   ├── gameApi.js          # game APIs
│   │   └── sessionMonitor.js   # session monitor API (debug)
│   ├── ws/
│   │   └── gomokuSocket.js     # Gomoku WebSocket service
│   └── index.js
│
├── config/
│   └── appConfig.js            # app config (API/WS URLs)
│
└── styles/
    ├── global.css
    ├── header.css
    ├── home.css
    ├── lobby.css
    ├── game.css
    ├── components.css
    └── sessionMonitor.css
```

---

## Business Logic Details

**Note**: Describes current Gomoku logic. game-service is modular; new games can implement their own.

### 4.1 Room Lifecycle

#### 4.1.1 Create Room
**Flow**:
1. `POST /api/gomoku/new` with `mode` (PVP/PVE), `aiPiece`, `rule`.
2. Check ongoing room (OngoingGameTracker); if exists, 409 conflict.
3. Generate `roomId` and `gameId` (UUID).
4. Create in Redis:
   - `RoomMeta` (mode, rule, owner, createdAt, current gameId, scores)
   - `GameStateRecord` (first game: empty board, black to move)
   - `SeatsBinding` (owner binds black by default, seatXSessionId)
   - Room index (ZSET) for lobby list descending by createdAt
5. Record in `OngoingGameTracker` (frontend “continue”).
6. Cache owner user info in Redis (avoid Feign in WS).
7. Create in-memory Room for fast access.

**Key code**: `GomokuServiceImpl.newRoom()`

#### 4.1.2 Join Room
**Flow**:
1. `POST /api/gomoku/rooms/{roomId}/join`
2. Check already in room (seat bound)
3. Check other ongoing room
4. `resolveAndBindSide()` assigns seat with concurrent seat lock:
   - **PVE**: player auto assigned opposite AI
   - **PVP**: auto assign empty seat (black then white); no intention seat
   - **Concurrent seat**: light SETNX lock (TTL 2m) per room+seat; release immediately after write. Lock only for mutex; truth in Redis `SeatsBinding`.
5. Cache user info
6. Record in `OngoingGameTracker`
7. Broadcast `SNAPSHOT`

**Key code**: `GomokuServiceImpl.resolveAndBindSide()`

Concurrent seat notes:
- Redis key `gomoku:room:{roomId}:seatLock:{X|O}` with SETNX+TTL.
- Entry `resolveAndBindSide` → `tryLockSeat` → write `SeatsBinding` → `releaseSeatLock`.
- TTL 2m; normally released immediately.
- Locations: `GomokuServiceImpl` and Redis lock in `RedisRoomRepository` / `RedisOps`.

#### 4.1.3 Leave Room
**Flow**:
1. `POST /api/gomoku/rooms/{roomId}/leave`
2. **PVE**: destroy room
3. **PVP**:
   - If opponent absent: destroy room
   - If opponent present: release seat, transfer owner (if owner leaves), clear game state, reset phase to `WAITING`
4. Clear `OngoingGameTracker`
5. If not destroyed, broadcast `SNAPSHOT`

**Key code**: `GomokuServiceImpl.leaveRoom()`

#### 4.1.4 Lifecycle Issues
Current lifecycle works but has design gaps:
1. **State transitions lack unified mgmt**: `setRoomPhase()` directly sets without rules.
2. **State validation incomplete**: only some ops (e.g., `startGame()`) validate; many direct set.
3. **ENDED unused**: defined but game ends go back to `WAITING`, skipping `ENDED`.
4. **No concurrency control**: state changes lack distributed lock; multi-node may diverge.
5. **Overloaded class**: all state logic in `GomokuServiceImpl`; no dedicated state manager.

**Improvement**: introduce state machine, unified transitions, locks, use `ENDED`.

### 4.2 Game Flow

#### 4.2.1 Ready Phase (WAITING)
**State**: `RoomPhase.WAITING`

- Players toggle ready (`toggleReady()`)
- **PVE**: only owner ready (AI ready by default)
- **PVP**: all players ready (at least 2 players)
- Owner can click “start” (`startGame()`)
- **WAITING cannot place stones** (frontend & backend enforced)

**Key code**:
- `GomokuServiceImpl.toggleReady()`
- `GomokuServiceImpl.startGame()` (owner check, ready check, WAITING → PLAYING)

#### 4.2.2 Playing Phase (PLAYING)
**State**: `RoomPhase.PLAYING`
- Players can place (`place()`)
- Turn auto switches (black/white)
- Countdown starts (`TurnClockCoordinator`)
- Win check (`GomokuJudge`)
- **PVE**: AI moves with 1–1.5s delay

**Key code**:
- `GomokuServiceImpl.place()`
- `GomokuWsController.place()`
- `TurnClockCoordinator.syncFromState()`

#### 4.2.3 End Handling
**Triggers**:
- Five-in-row (`GomokuJudge.isWin()`)
- Board full (draw)
- Resign (`resign()`)
- Timeout (`TurnClockCoordinator.handleTimeout()`)

**Process**:
1. Update score (`incrSeriesOnFinish()`)
2. Clear turn anchor (`turnRepo.delete()`)
3. Reset ready (`resetAllReady()`)
4. Set phase `WAITING` (next game re-ready)
5. Broadcast `STATE` and `SNAPSHOT`

### 4.3 Move Logic

#### 4.3.1 Player Move
**Flow**:
1. Frontend sends `place` to `/app/gomoku.place`
2. **PVE auth**: only owner moves
3. Seat binding (if seatKey provided, bind seat)
4. Backend checks:
   - Phase must be `PLAYING`
   - Must be current side
   - Coord valid and empty
   - Forbidden move check (RENJU, black only)
5. Compute CAS expected (`expectedStep`: stones count, `expectedTurn`: side)
6. Apply move (`GomokuState.apply()`)
7. Outcome (`GomokuJudge.outcomeAfterMove()`)
8. If ongoing, switch turn; if ended, update score, reset ready, phase → WAITING
9. **CAS persist** to Redis (`updateAtomically()`)
10. Broadcast `STATE` and `SNAPSHOT`
11. Sync countdown (`TurnClockCoordinator.syncFromState()`)
12. **PVE**: if AI turn, delay 1–1.5s then AI move

**Key code**: `GomokuWsController.place()`

#### 4.3.2 AI Move (PVE)
**Flow**:
1. After player move, if AI turn, delay 1–1.5s
2. `GomokuAI.bestMove()` to get suggestion
3. Execute AI move (reuse `place()`)
4. Save and broadcast
5. Use `gameId` to prevent cross-game execution (cancel if restarted)

**Key code**:
- `GomokuWsController.maybeScheduleAi()` schedules AI (delay 1–1.5s)
- `GomokuWsController.runAiTurn()` executes AI (validate gameId)

### 4.4 Countdown System

#### 4.4.1 Architecture
- **Generic engine**: `CountdownScheduler` (business-agnostic)
- **Business coordinator**: `TurnClockCoordinator` (Gomoku rules)

#### 4.4.2 Workflow
1. **Startup**: `TurnClockCoordinator.onReady()` registers TICK listener and restores active tasks
   - TICK: every second broadcast countdown to room (`/topic/room.{roomId}`)
   - Restore: resume unexpired timers (expired trigger timeout once)
2. **Sync**: on state change, `syncFromState()`:
   - Ended: stop
   - Phase not PLAYING: stop
   - PVE and AI not timed and AI turn: stop (`aiTimed=false`)
   - Else: start/resume countdown (key=`gomoku:{roomId}`, owner=`X|O`, version=`gameId`)
3. **TICK**: backend broadcasts remaining each second `{left, side, deadlineEpochMs}`
4. **Timeout**: on expiry, resign loser (`gomokuService.resign()`), broadcast `TIMEOUT/STATE/SNAPSHOT`

**Mechanism**:
- Backend computes and broadcasts; frontend only displays remaining (`left`), no self timer.
- If backend stops TICK (restart/network), frontend stays at last value; can detect missing TICK to warn.

**Key code**: `TurnClockCoordinator`

---

## Code Architecture Analysis

### 5.1 Layered Design

#### 5.1.1 Interface Layer (interfaces)
**Duties**:
- Receive HTTP/WS
- Validate params
- Call service layer
- Return response/broadcast

**Key classes**:
- `GomokuRestController`: REST (create/join/view/leave)
- `RoomListController`: list (paging)
- `GomokuWsController`: WS (place, resign, ready, start, restart, kick)
- `GomokuResumeController`: WS resume (refresh rejoin → FullSync)
- `OngoingGameController`: ongoing game query/clean (`/api/ongoing-game`)
- `MeController`: current user (`/me`)

#### 5.1.2 Service Layer
**Duties**:
- Core business
- Room lifecycle
- Game state
- Seat binding
- Ready status

**Key class**:
- `GomokuServiceImpl` (1267 lines core)

#### 5.1.3 Domain Layer
**Duties**:
- Models (Room, Game, GomokuState, Board)
- Rules (GomokuJudge, GomokuJudgeRenju)
- AI (GomokuAI, Evaluator)
- Repositories (RoomRepository, GameStateRepository)

**Key classes**:
- `Room`, `Game`, `GomokuState`, `Board`

#### 5.1.4 Application Coordination
**Duties**:
- Coordinate generic components with business
- Countdown coordination

**Key class**:
- `TurnClockCoordinator`

#### 5.1.5 Infrastructure Layer
**Duties**:
- Redis repos
- Feign client
- Configs

#### 5.1.6 Platform Layer
**Duties**:
- WS infra (session mgmt, disconnect)
- Ongoing game tracking

**Key class**:
- `WebSocketSessionManager`

### 5.2 Data Flow
```
HTTP/WS request
    ↓
Gateway
    ↓
Controller (interfaces)
    ↓
Service
    ↓
Domain ←→ Repository
    ↓               ↓
Redis           Feign (system-service)
```

### 5.3 State Management

#### 5.3.1 In-Memory
- `ConcurrentHashMap<String, Room> rooms`: room cache for fast access.
- If miss, load from Redis and cache.

#### 5.3.2 Redis
- **RoomMeta**: mode, rule, owner, createdAt, current gameId, score
- **GameStateRecord**: board string, current, winner, steps
- **SeatsBinding**: seatXSessionId, seatOSessionId, seatBySession, readyByUserId
- **TurnAnchor**: side, deadlineEpochMs, turnSeq
- **UserProfile**: user info cache (avoid Feign in WS)

---

## Frontend/Backend Interaction Flows

**Note**: Current Gomoku flows; other games may differ but follow same WS/HTTP mechanism.

### 6.0 REST Endpoints

#### 6.0.1 Gomoku (`/api/gomoku`)
| Method | Path | Description | Controller |
|--------|------|-------------|------------|
| POST | `/new` | Create room | `GomokuRestController` |
| GET  | `/rooms/{roomId}/view` | Get full snapshot | `GomokuRestController` |
| POST | `/rooms/{roomId}/join` | Join room | `GomokuRestController` |
| POST | `/rooms/{roomId}/leave` | Leave room | `GomokuRestController` |
| GET  | `/rooms?cursor=&limit=` | List rooms (paging) | `RoomListController` |

#### 6.0.2 Other
| Method | Path | Description | Controller |
|--------|------|-------------|------------|
| GET | `/api/ongoing-game` | Query ongoing | `OngoingGameController` |
| POST | `/api/ongoing-game/end` | End ongoing | `OngoingGameController` |
| GET | `/me` | Current user | `MeController` |

#### 6.0.3 WebSocket (`/app/gomoku.*`)
| Mapping | Description | Controller |
|---------|-------------|------------|
| `/gomoku.place` | Place | `GomokuWsController` |
| `/gomoku.resign` | Resign | `GomokuWsController` |
| `/gomoku.ready` | Ready toggle | `GomokuWsController` |
| `/gomoku.start` | Start (owner) | `GomokuWsController` |
| `/gomoku.restart` | Restart | `GomokuWsController` |
| `/gomoku.resume` | Resume (refresh rejoin) | `GomokuResumeController` |
| `/gomoku.kick` | Owner kicks | `GomokuWsController` |

### 6.1 Create Room Flow
```
POST /api/gomoku/new?mode=PVE&rule=STANDARD
    ↓
GomokuRestController.newRoom()
    ↓
Check ongoing (OngoingGameTracker)
    ↓
GomokuServiceImpl.newRoom()
    ↓
Redis: RoomMeta, GameStateRecord, SeatsBinding, room index
    ↓
Record OngoingGameTracker
    ↓
Cache owner profile
    ↓
Return roomId
```

### 6.2 Get Room Snapshot
```
GET /api/gomoku/rooms/{roomId}/view
    ↓
GomokuRestController.getRoomView()
    ↓
GomokuService.snapshot(roomId)
    ↓
Redis aggregate:
  - RoomMeta
  - SeatsBinding
  - GameStateRecord
  - TurnAnchor
  - UserProfile cache
    ↓
Query WS connection (SessionRegistry)
    ↓
Build GomokuSnapshot
    ↓
Return full snapshot
```

### 6.3 WebSocket Connect & Resume
```
WS connect (/game-service/ws?access_token=...)
    ↓
WebSocketSessionManager.handleSessionConnect()
    ↓
Register session
    ↓
Client subscribe (/topic/room.{roomId})
    ↓
Client send resume (/app/gomoku.resume with seatKey)
    ↓
GomokuResumeController.onResume()
    ↓
If seatKey provided, bind via bindBySeatKey()
    ↓
Build full snapshot (GomokuSnapshot)
    ↓
Convert to FullSync DTO
    ↓
P2P push (/user/queue/gomoku.full)
```

### 6.4 Move Flow
```
WS /app/gomoku.place
    ↓
GomokuWsController.place()
    ↓
1) Auth (PVE only owner)
2) Bind seat (if seatKey)
3) GomokuService.place():
   - validate phase/turn/legality/forbidden
   - GomokuState.apply()
   - GomokuJudge.outcomeAfterMove()
4) CAS save (expectedStep/expectedTurn)
5) sendState(): STATE event
6) broadcastSnapshot(): SNAPSHOT event
7) TurnClockCoordinator.syncFromState()
8) If PVE and AI turn, delay 1–1.5s AI move
```

### 6.5 Ready/Start Flow
```
WS /app/gomoku.ready
    ↓
GomokuWsController.ready()
    ↓
GomokuService.toggleReady()
    ↓
Broadcast SNAPSHOT
```
```
WS /app/gomoku.start
    ↓
GomokuWsController.startGame()
    ↓
GomokuService.startGame() → check ready, phase WAITING→PLAYING
    ↓
Broadcast SNAPSHOT + STATE
```

### 6.6 WebSocket Event Types
| Event | Description | Payload | Topic |
|-------|-------------|---------|-------|
| `STATE` | Game state delta | `{ state: GomokuState, series: SeriesView }` | `/topic/room.{roomId}` |
| `SNAPSHOT` | Full room snapshot | `GomokuSnapshot` | `/topic/room.{roomId}` |
| `TICK` | Countdown update | `{ left, side: 'X'|'O', deadlineEpochMs }` | `/topic/room.{roomId}` |
| `TIMEOUT` | Timeout loss | `{ side: 'X'|'O' }` | `/topic/room.{roomId}` |
| `ERROR` | Error message | `string` | `/topic/room.{roomId}` |
| `READY_STATUS` | Ready update (deprecated; use SNAPSHOT) | `Map<userId, ready>` | `/topic/room.{roomId}` |
| `ROOM_STATUS` | Room phase (deprecated; use SNAPSHOT) | `{phase: "WAITING"|"PLAYING"}` | `/topic/room.{roomId}` |

**P2P**:
- `/user/queue/gomoku.seat`: seatKey push
- `/user/queue/gomoku.full`: FullSync (refresh rejoin)

---

## Data Model and Storage

**Note**: Current Gomoku data model; other games may differ but reuse Redis infra.

### 7.1 Redis Keys (Gomoku)

#### 7.1.1 Room Meta
```
Key: gomoku:room:{roomId}
Type: HASH
TTL: 48h
Fields:
  - roomId
  - gameId (current game)
  - mode (PVP/PVE)
  - rule (STANDARD/RENJU)
  - aiPiece (X/O/null)
  - currentIndex
  - blackWins
  - whiteWins
  - draws
  - ownerUserId
  - ownerName
  - createdAt
  - phase (WAITING/PLAYING)
```

#### 7.1.2 Game State
```
Key: gomoku:room:{roomId}:game:{gameId}:state
Type: HASH
TTL: 48h
Fields:
  - roomId
  - gameId
  - index
  - board (225 chars, '.' empty, 'X'/'O')
  - current (X/O)
  - lastMove ("x,y")
  - winner (X/O/DRAW/null)
  - over (Boolean)
  - step (stone count, CAS)
```

#### 7.1.3 Seats Binding
```
Key: gomoku:room:{roomId}:seats
Type: HASH
TTL: 48h
Fields:
  - seatXSessionId
  - seatOSessionId
  - seatBySession:{userId} -> X/O
  - readyByUserId:{userId} -> Boolean
```

#### 7.1.4 Turn Anchor
```
Key: gomoku:room:{roomId}:turn
Type: HASH
TTL: dynamic (deadline)
Fields:
  - side (X/O)
  - deadlineEpochMs
  - turnSeq
```

#### 7.1.5 SeatKey
```
Key: gomoku:room:{roomId}:seatKey:{seatKey}
Type: String
Value: X or O
TTL: 48h
Use: resume binding after refresh
```

#### 7.1.6 Room Index
```
Key: gomoku:rooms:index
Type: ZSET
Score: createdAt
Member: roomId
Use: lobby list desc by createdAt
```

#### 7.1.7 User Profile Cache
```
Key: gomoku:room:{roomId}:users
Type: HASH
Field: {userId}
Value: UserProfileView (serialized)
TTL: 30m
Use: cache room players to avoid Feign in WS
```

#### 7.1.8 Series Stats
```
Key: gomoku:room:{roomId}:series
Type: HASH
TTL: 48h
Fields:
  - blackWins
  - whiteWins
  - draws
  - index (current round)
```

#### 7.1.9 User Ongoing Game
```
Key: gomoku:user:{userId}:ongoing
Type: String
Value: OngoingGameInfo (serialized)
TTL: 48h
Use: track current game for “continue”
```

### 7.2 Domain Models (Gomoku)

#### 7.2.1 Room
```java
public class Room {
    private final String id;
    private final Mode mode;
    private final Rule rule;
    private final char aiPiece;
    private final GomokuAI ai;
    private final Series series;
    private final Map<String, Character> seatBySession;
    private volatile String seatXSessionId;
    private volatile String seatOSessionId;
}
```

#### 7.2.2 Game
```java
public class Game {
    private String gameId;
    private final int index;
    private final GomokuState state;
    private volatile ScheduledFuture<?> pendingAi;
}
```

#### 7.2.3 GomokuState
```java
public class GomokuState {
    private final Board board;
    private char current;
    private Move lastMove;
    private Character winner;
    
    public boolean over() { ... }
    public void apply(Move move) { ... }
}
```

#### 7.2.5 GomokuSnapshot
```java
public final class GomokuSnapshot {
    public final String roomId;
    public final boolean seatXOccupied;
    public final boolean seatOOccupied;
    public final String seatXUserId;
    public final String seatOUserId;
    public final UserProfileView seatXUserInfo;
    public final UserProfileView seatOUserInfo;
    public final boolean seatXConnected;
    public final boolean seatOConnected;
    public final String ownerUserId;
    public final long createdAt;
    public final String mode;
    public final Character aiSide;
    public final String rule;
    public final String phase;
    public final int boardSize;
    public final char[][] cells;
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

#### 7.2.4 Board
```java
public class Board {
    public static final int SIZE = 15;
    public static final char EMPTY = '.';
    public static final char BLACK = 'X';
    public static final char WHITE = 'O';
    
    private final char[][] cells;
    
    public void place(int x, int y, char piece) { ... }
    public char get(int x, int y) { ... }
    public boolean isEmpty(int x, int y) { ... }
}
```

---

## Key Technical Implementations

**Note**: Generic and Gomoku-specific implementations. Generic can be reused by other games.

### 8.1 CAS (Compare-And-Swap)

**Problem**: concurrent moves may diverge state.

**Solution**:
- Use `step` (stones count) and `current` (side to move) as CAS expected values.
- Redis `HGETALL` + `HSET` + Lua for atomic update.

**Key code**:
```java
// GomokuWsController.place()
int expectedStep = computeExpectedStep(before.board());
char expectedTurn = before.current();
// ... apply move ...
gameStateRepository.updateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, nextDeadlineMillis);
```

### 8.2 WebSocket Session Management

**Note**: Details in SSO doc; not covered here.

**Brief**:
- Use `SessionRegistry` for WS sessions.
- On new connection, kick old session of same user.
- See `WebSocketSessionManager`.

### 8.3 Refresh Rejoin

**Problem**: refresh needs seat recovery.

**Solution**:
- On first seating, backend issues `seatKey` (Base64URL random).
- Frontend saves `seatKey` to localStorage.
- After refresh, use `seatKey` to restore seat.

**Key code**:
```java
// GomokuServiceImpl.issueSeatKey()
String key = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
roomRepo.setSeatKey(roomId, key, String.valueOf(s), ROOM_TTL);

// GomokuServiceImpl.bindBySeatKey()
Character seat = roomRepo.getSeatKey(roomId, seatKey);
// ... bind seat ...
```

### 8.4 Countdown System

**Architecture**:
- Generic engine: `CountdownScheduler`
- Business coordinator: `TurnClockCoordinator`

**Implementation**:
- Store turn anchor (`TurnAnchor`) in Redis.
- Scheduler checks every second and broadcasts TICK.
- Timeout callback does forfeit.

**Mechanism**:
- Backend broadcasts `TICK` with `left/side/deadlineEpochMs`; frontend only displays.
- If backend stops, frontend stays at last value; can detect missing TICK to warn.

**Key code**:
```java
// TurnClockCoordinator.syncFromState()
if (state.over() || phase != RoomPhase.PLAYING) { stop(roomId); return; }
if (aiTurn && !aiTimed) { stop(roomId); return; }
String key = "gomoku:" + roomId;
String owner = String.valueOf(sideToMove);
String version = gomokuService.getGameId(roomId);
long deadline = System.currentTimeMillis() + turnSeconds * 1000L;
scheduler.startOrResume(key, owner, deadline, version, (k, o, v) -> handleTimeout(k, o));
```

### 8.5 AI (Gomoku)

**Algorithm**: threat-first + alpha-beta search + forbidden filter.

**Notes**: AI is game-specific; each game can implement its own strategy.

**Key classes**:
- `GomokuAI`
- `Evaluator`

**Delayed execution**:
- After player move, delay 1–1.5s for AI (simulate thinking).
- Dedicated delay pool `aiScheduler` (from `AiSchedulerConfig`), separate from countdown.
- Use `gameId` to prevent cross-game execution; cancel when new game.
- `ConcurrentHashMap<String, ScheduledFuture<?>> pendingAi` to debounce; one AI task per room.

**Key code**:
```java
// GomokuWsController.maybeScheduleAi()
ScheduledFuture<?> old = pendingAi.remove(roomId);
if (old != null) old.cancel(false);
long delay = 1000 + ThreadLocalRandom.current().nextLong(501);
ScheduledFuture<?> fut = aiScheduler.schedule(() -> runAiTurn(roomId, gameIdAtSchedule, ai), delay, TimeUnit.MILLISECONDS);
pendingAi.put(roomId, fut);

// GomokuWsController.runAiTurn()
if (!gameIdAtSchedule.equals(gomokuService.getGameId(roomId))) return;
```

### 8.6 Rule Checking (Gomoku)

#### 8.6.1 Standard
- Five in a row wins.
- Board full → draw.

**Key**: `GomokuJudge`

#### 8.6.2 Renju Forbidden
- Black forbidden: overline, double-four, double-three.
- White no forbidden.

**Key**: `GomokuJudgeRenju`

---

## Code Details Analysis

**Note**: Current Gomoku code; structure supports multiple games under `games/{gameName}/`.

### 9.1 Backend Key Classes

#### 9.1.1 GomokuServiceImpl (core, 1175 lines)
**Duties**:
- Room lifecycle (create/join/leave/destroy/kick)
- Game state (place/resign/restart/new game)
- Seat binding (assign/restore/seatKey)
- Ready status (toggle/get/reset)
- Room phase (WAITING/PLAYING)
- Snapshot (`snapshot()`)
- User profile cache (avoid Feign in WS)

**Key methods**:
- `newRoom()` create room (check ongoing, Redis data, OngoingGameTracker)
- `room()` get room (memory first, then Redis backfill)
- `place()` validate state/turn/legality/forbidden, apply move, outcome
- `resolveAndBindSide()` assign/restore seat (PVE auto assign; PVP supports specified)
- `leaveRoom()` leave (PVE destroy; PVP release seat, transfer owner, clear state)
- `kickPlayer()` owner kicks (WAITING, PVP)
- `snapshot()` aggregate all data from Redis (multi-node)
- `cacheUserProfile()` cache user info (avoid WS Feign)

**Patterns**:
- Repository pattern for Redis
- Strategy for PVE/PVP handling
- Memory + Redis dual store (memory cache, Redis persistence/share)

#### 9.1.2 GomokuWsController (WS, 434 lines)
**Duties**:
- Handle WS messages (place, resign, ready, start, restart)
- Broadcast state (STATE, SNAPSHOT, ERROR)
- AI scheduling (PVE)

**Key methods**:
- `place()` (PVE auth, seat bind, CAS save)
- `maybeScheduleAi()` (delay 1–1.5s, gameId guard)
- `runAiTurn()` (validate gameId)
- `sendState()` (STATE + SNAPSHOT)
- `broadcastSnapshot()` (full snapshot)
- `kickPlayer()` (owner kick, WAITING, PVP)

**Design points**:
- `gameId` prevents AI cross-game
- CAS to prevent concurrent divergence
- Unified `broadcastSnapshot()` for full room (multi-node)
- PVE auth: only owner can move/resign/restart

#### 9.1.3 TurnClockCoordinator (countdown, 267 lines)
**Duties**:
- Bridge countdown engine and Gomoku rules
- Register TICK and restore tasks
- Sync countdown by game state
- Handle timeout

**Key methods**:
- `onReady()` register TICK and restore active tasks
- `syncFromState()` decide start/stop based on state
- `handleTimeout()` forfeit, CAS save, broadcast TIMEOUT/STATE/SNAPSHOT

**Design points**:
- Use `gameId` as version to prevent cross-game
- Stop on end/non-PLAYING/PVE no AI timing
- Broadcast TICK every second

#### 9.1.4 WebSocketSessionManager (WS session)
**Duties**:
- Listen WS connect/disconnect
- Session mgmt (details in SSO doc)
- On disconnect broadcast snapshot (update connection status)

**Key methods**:
- `handleSessionConnect()`: register, kick old
- `handleSessionDisconnect()`: unregister, broadcast snapshot connection status

**Design points**:
- Register on connect, unregister on disconnect
- Clean session to keep snapshot connection status correct

### 9.2 Frontend Key Pieces

#### 9.2.0 App Routes (App.jsx, 47 lines)
**Routes**:
- `/`: HomePage
- `/lobby`: LobbyPage
- `/game/:roomId`: GameRoomPage
- `/profile`: ProfilePage
- `/sessions`: SessionMonitorPage (debug)
- `*`: NotFoundPage

**Layout**:
- Wrapped in `<AppLayout>`: Header + Outlet + GlobalChat

#### 9.2.1 useGomokuGame (hook, 748 lines)
**Duties**:
- Manage game state (board, turn, score, countdown)
- WS connection
- Handle WS events (STATE, SNAPSHOT, TICK, ERROR)
- Provide actions (place, resign, ready, start)

**Key state**:
- `board` 15x15
- `sideToMove`
- `mySide`
- `countdown`
- `readyStatus`
- `roomPhase`

**Key methods**:
- `handleRoomEvent()` for room events
- `handleFullSync()` full sync update
- `placeStone()` action (checks)
- `updateGameState()` board/turn/outcome
- `updateSeriesInfo()` scores/round

**Design**:
- First screen via HTTP snapshot (no WS dependency)
- After WS connect, send resume with seatKey
- Dual update: STATE (delta) + SNAPSHOT (full)

#### 9.2.2 GameRoomPage
**Duties**:
- Render board, players, chat, system log
- Handle interactions (place/resign/ready/start)
- Show status (turn, score, countdown)

**Key components**:
- `GomokuBoard`, `PlayerCard`, `GameChatPanel`, `SystemInfoPanel`

**Design**:
- Uses `useGomokuGame`
- Show victory modal on end
- UI differs by phase

#### 9.2.3 gomokuSocket (WS service, 255 lines)
**Duties**:
- WS connection (SockJS + STOMP)
- Subscribe room events, seatKey, full sync
- Send actions (place/resign/ready/start/resume/restart/kick)

**Key methods**:
- `connectWebSocket()` (token in URL param)
- `subscribeRoom()`, `subscribeSeatKey()`, `subscribeFullSync()`, `subscribeKicked()`
- `sendResume()`, `sendPlace()`, `sendResign()`, `sendRestart()`, `sendReady()`, `sendStartGame()`, `sendKick()`
- `disconnectWebSocket()`, `isConnected()`

**Design**:
- Token via URL param (SockJS cannot set headers)
- Unified error handling
- Map-managed subscriptions; cleanup on disconnect
- 10s connect timeout

**Note**: Token acquisition in SSO doc.

#### 9.2.4 OngoingGameContext
**Duties**:
- Manage current ongoing game state
- Provide refresh/end

**State**:
- `loading`, `data` (hasOngoing, gameType, roomId, title), `error`
- Methods: `refresh()`, `end(roomId)`

#### 9.2.6 LobbyPage (591 lines)
**Duties**:
- Mode selection (PVE, create room, matchmaking placeholder)
- Online room list (paging, refresh, load more)
- Create/join/enter room

**Features**:
- PVE: create room, choose rule
- Create room: PVP with rule choice
- Matchmaking: placeholder
- Room list: show online PVP, filter PVE/full/deleted; join/spectate
- Enter by roomId

**State**:
- `rooms`, `roomsCursor`, `roomsHasMore`, `refreshingRooms`, `loadingMoreRooms`

#### 9.2.7 HomePage (336 lines)
**Duties**:
- Welcome page
- Carousel of recommended games
- Ongoing prompt
- Game categories (hot/new/strategy-board)

**Interactions**:
- Click Gomoku card → `/lobby`
- Click “continue” → `/game/{roomId}`

#### 9.2.8 apiClient (103 lines)
**Duties**:
- Wrap HTTP
- Unified errors

**Key**:
- `authenticatedFetch()`, `authenticatedJsonFetch()`
- `get/post/put/del`
- Parse ApiResponse

**Note**: Token/401 handling in SSO doc.

#### 9.2.9 gameApi (201 lines)
**Duties**:
- Wrap all game HTTP APIs

**Key methods**:
- `createRoom()`, `getMe()`, `getOngoingGame()`, `endOngoingGame()`, `joinRoom()`, `leaveRoom()`, `listGomokuRooms()`, `getRoomView()`, `getUserInfos()`, `getUserInfo()`

### 9.3 Data Flow Details

#### 9.3.0 Frontend State Layers
**Layers**:
1. **Global (Context)**: `OngoingGameContext`
2. **Page state (useState)**: `GameRoomPage`, `LobbyPage`
3. **Business hooks**: `useGomokuGame`, `useOngoingGame`

**Updates**:
```
WS event → handleRoomEvent() → hook state → render
HTTP response → update context/hook → render
User action → hook method → WS/HTTP → response → state update
```

#### 9.3.1 Move Data Flow
```
Click board
    ↓
useGomokuGame.placeStone()
    ↓
gomokuSocket.sendPlace(roomId, x, y, side, seatKey)
    ↓
STOMP /app/gomoku.place
    ↓
GomokuWsController.place()
    ↓
before state → expectedStep/Turn → GomokuService.place()
   validate phase/turn/legality/forbidden
   GomokuState.apply()
   GomokuJudge.outcomeAfterMove()
   switch turn if ongoing
buildRecord → persistStateAtomically (CAS)
sendState (STATE)
broadcastSnapshot (SNAPSHOT)
TurnClockCoordinator.syncFromState()
    ↓
Frontend receives STATE
    ↓
handleRoomEvent → updateGameState
    ↓
GameRoomPage render
```

#### 9.3.2 Snapshot Generation (Backend)
```
GomokuService.snapshot(roomId)
    ↓
assembleRoomView:
  Redis RoomMeta
  Redis SeatsBinding
  Redis GameStateRecord
  Redis TurnAnchor
query user info (cache first)
query WS connection (SessionRegistry)
build GomokuSnapshot:
  room info
  seats
  user info
  connection status
  board
  game state (sideToMove, round, scores, outcome)
  countdown (turnSeq, deadline)
  readyStatus
    ↓
return GomokuSnapshot
```

#### 9.3.3 Snapshot Handling (Frontend)
```
SNAPSHOT (WS) or HTTP
    ↓
handleRoomEvent/handleFullSync
    ↓
handleFullSync(snap):
  update board (parse cells/board)
  update game state (sideToMove, round, scoreX/O, outcome)
  update room state (phase, readyStatus, mode, aiSide)
  update seats (userIds, userInfo, connected)
  update mySide, seatKey (save localStorage)
  update countdown (turnSeq, deadlineEpochMs)
    ↓
render
```

---

## Frontend Detailed Analysis

**Note**: Frontend for Gomoku; modular for other games too.

### 10.0 Pages

#### 10.0.1 HomePage
**Features**:
- Welcome
- Carousel hot games
- Ongoing prompt
- Game categories (hot/new/strategy/board)

**Interactions**:
- Click Gomoku → `/lobby`
- Click continue → `/game/{roomId}`

#### 10.0.2 LobbyPage
**Features**:
- **PVE**: create PVE (rule STANDARD/RENJU) then jump
- **Create room**: PVP with rule choice then jump
- **Matchmaking**: placeholder
- **Room list**: online PVP (filter PVE/full/deleted), refresh/paging, info (owner/rule/status/players), join/spectate
- **Enter room**: by roomId

**State**: rooms, cursor, hasMore, refreshingRooms, loadingMoreRooms; modal states.

#### 10.0.3 GameRoomPage
**Features**:
- Board render (15x15, last move, win line)
- Player info (avatar/name/seat/countdown/ready)
- Game status (turn, score, state)
- Buttons: ready/unready (WAITING), start (owner, all ready), resign (PLAYING), leave
- Chat panel (local, no backend)
- System log

**Interactions**:
- Click cell → place (requires PLAYING, my turn, not ended)
- Ready toggle
- Start (owner)
- Resign
- Leave (confirm)

#### 10.0.4 ProfilePage
**Features**: placeholder; future profile/stats/preferences.

#### 10.0.5 SessionMonitorPage
**Features**: debug
- Show all online sessions
- Poll every 2s
- Show status (ACTIVE/KICKED/EXPIRED)

#### 10.0.6 NotFoundPage
**Features**: 404 with links to home/lobby.

### 10.1 Components

#### 10.1.1 AppLayout
**Duties**: layout with Header + Outlet + GlobalChat, wrapped by `OngoingGameProvider`.

#### 10.1.2 Header
**Features**:
- Show user info
- Show ongoing prompt

**Logic**:
- From `OngoingGameContext`; “continue” jumps to room.

**Note**: user info fetch in SSO doc.

#### 10.1.3 GlobalChat
**Features**:
- Global chat (collapsible)
- Local storage for collapse state and messages
- Send messages

### 10.2 Services

#### 10.2.1 apiClient
**Features**:
- Wrap HTTP
- Handle 401
- Unified errors

**Key**:
- `authenticatedFetch`, `authenticatedJsonFetch`, GET/POST/PUT/DELETE
- Parse `{code,message,data}`

**Note**: token/401 in SSO doc.

#### 10.2.2 gameApi
**Features**:
- Wrap game APIs: createRoom, getMe, getOngoingGame, endOngoingGame, joinRoom, leaveRoom, listGomokuRooms, getRoomView, getUserInfos, getUserInfo.

#### 10.2.3 gomokuSocket
**Features**:
- WS connect (token in URL param), subscribe room/seatKey/full sync/kicked, send actions.

**Flow**:
1. Get token (see SSO)
2. Pass via URL param `/game-service/ws?access_token=...`
3. SockJS connect
4. STOMP
5. Set headers (Authorization bearer)

**Design**:
- Map subscriptions, cancel on disconnect; unified error; 10s timeout.

### 10.3 State Management

#### 10.3.1 Global (Context)
**OngoingGameContext**: loading/data/error; methods refresh/end.

#### 10.3.2 Business Hooks
**useGomokuGame**: all game state, WS, handlers, actions.  
**useOngoingGame**: extract ongoing game, helpers.

### 10.4 Data Flow

#### 10.4.1 Init
```
App start
  ↓
OngoingGameProvider init → fetch ongoing
  ↓
Render App → routes
```

#### 10.4.2 Enter Room
```
/game/{roomId}
  ↓
GameRoomPage mount
  ↓
useGomokuGame init
  ↓
1) HTTP GET /api/gomoku/rooms/{roomId}/view → handleFullSync → first render
2) WS connect → register, kick old → subscribe room/seatKey/full → send resume with seatKey → receive FullSync → handleFullSync
```

#### 10.4.3 State Updates
```
WS event → subscribeRoom callback → handleRoomEvent
  - STATE → updateGameState
  - SNAPSHOT → handleFullSync
  - TICK → setCountdown
  - TIMEOUT → update outcome
  - ERROR → onMessage
→ render
```

### 10.5 Frontend Techniques

#### 10.5.1 First Paint
- Use HTTP snapshot first; WS async.
- Even without WS, state visible.

**Code**:
```javascript
useEffect(() => {
  getRoomView(roomId).then(handleFullSync)
}, [roomId])
```

#### 10.5.2 State Sync
- Dual: **STATE** (delta) + **SNAPSHOT** (full).
- Lightweight updates + full consistency.

#### 10.5.3 Refresh Resume
- seatKey issued, stored localStorage; resume uses seatKey to restore.

**Code**:
```javascript
subscribeSeatKey((seatKey, side) => {
  seatKeyRef.current = seatKey
})
sendResume(roomId, seatKeyRef.current)
```

#### 10.5.4 Error Handling
- HTTP: apiClient unified.
- WS: gomokuSocket unified.
- User messages via onMessage.
- Network vs business errors.

---

## Future Improvement Plan

### 11.1 Current Issues

#### 11.1.1 Code Organization
- **GomokuServiceImpl too large** (1175 lines): plan to split
  - RoomManagementService
  - GameStateService
  - SeatManagementService
- **Memory + Redis dual store**: possible inconsistency
  - Current: memory first, fallback Redis
  - Plan: Redis as source of truth, memory as cache with sync/event

#### 11.1.2 Performance
- **findRoomByUserId() inefficient**: scans all rooms
  - Plan: user→room index (Redis SET)
- **AI scheduling**: in-memory map; multi-node may duplicate
  - Plan: Redis distributed lock

#### 11.1.3 Error Handling
- Some exceptions ignored (`catch (Exception ignore)`)
  - Plan: log at least
- CAS failure no retry
  - Plan: retry to improve success

#### 11.1.4 Code Concerns
- **useGomokuGame hook large** (748 lines): plan split
  - State hook
  - WS hook
  - Action hook
- **GameRoomPage large**: split components
  - Board
  - Player panel
  - Chat

### 11.2 Optimization Directions

#### 11.2.1 Architecture
1. Domain events for create/join/end.
2. CQRS for read/write split.
3. MQ for async AI/state sync.

#### 11.2.2 Performance
1. Redis Pipeline.
2. Local cache (Caffeine) for user/room meta.
3. WS connection pool reuse.

#### 11.2.3 Observability
1. Metrics: room count, online players, matches, AI latency.
2. Tracing: move request trace.
3. Log aggregation.

#### 11.2.4 Testing
1. Unit tests: rule check, AI.
2. Integration: lifecycle, move flow.
3. Stress: concurrent moves, many rooms.

---

## Summary

### Pros

#### Backend Pros
1. **Clear layering**: interface/service/domain/infra separated
2. **State mgmt**: Redis + memory cache
3. **Real-time**: WebSocket + STOMP
4. **Concurrency**: CAS for moves
5. **WS session mgmt**: register/disconnect
6. **Countdown**: generic engine + coordinator

#### Frontend Pros
1. **Componentized**: pages/components/hooks
2. **State mgmt**: Context + Hooks
3. **Routing**: unified config
4. **Error handling**: unified
5. **UX**: HTTP first paint + WS realtime

### To Improve

#### Backend
1. Split large core classes (GomokuServiceImpl)
2. Performance (findRoomByUserId)
3. Error handling (no silent catch)
4. Tests

#### Frontend
1. Split large hook/page (useGomokuGame, GameRoomPage)
2. Error handling (avoid console-only)
3. Performance (reduce rerenders)
4. Type safety (migrate to TS)

### Technical Highlights

#### Backend
1. **CAS**: step+current as expected
2. **Countdown**: engine + coordinator
3. **Refresh resume**: seatKey
4. **WS session mgmt**: register/disconnect
5. **Snapshot**: SNAPSHOT full broadcast, multi-node ready

#### Frontend
1. **First paint**: HTTP snapshot
2. **State sync**: STATE+SNAPSHOT
3. **Refresh resume**: seatKey + FullSync
4. **Realtime UX**: WS events for board/countdown/ready
5. **UX details**: end modal, forbidden hints, connection status

---

**Doc version**: v1.0  
**Last update**: 2025  
**Maintainer**: Dev team

