# 房间管理实现详细分析文档

## 一、架构概览

### 1.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Interface Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ HTTP REST    │  │ WebSocket    │  │ Resume       │      │
│  │ Controller   │  │ Controller   │  │ Controller   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │         GomokuServiceImpl (核心业务逻辑)              │   │
│  │  - 房间生命周期管理                                    │   │
│  │  - 座位绑定管理                                        │   │
│  │  - 游戏状态管理                                        │   │
│  │  - 准备状态管理                                        │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      Domain Layer                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Room         │  │ RoomMeta     │  │ SeatsBinding │      │
│  │ (内存对象)    │  │ (Redis DTO)  │  │ (Redis DTO)  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  Infrastructure Layer                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │         RedisRoomRepository                           │   │
│  │  - RoomMeta 持久化                                     │   │
│  │  - SeatsBinding 持久化                                 │   │
│  │  - SeatKey 管理                                        │   │
│  │  - Series 统计                                         │   │
│  │  - RoomIndex (ZSET)                                   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 数据存储结构

#### Redis 键空间设计

| Key Pattern | 类型 | 用途 | TTL |
|------------|------|------|-----|
| `gomoku:room:{roomId}` | String (JSON) | 房间元信息 (RoomMeta) | 48h |
| `gomoku:room:{roomId}:seats` | String (JSON) | 座位绑定 (SeatsBinding) | 48h |
| `gomoku:room:{roomId}:seatkey:{seatKey}` | String | SeatKey -> 座位映射 | 48h |
| `gomoku:room:{roomId}:series` | Hash | 系列赛统计 (round/blackWins/whiteWins/draws) | 48h |
| `gomoku:rooms:index` | ZSET | 在线房间索引 (roomId -> createdAt) | 48h |
| `gomoku:room:{roomId}:game:{gameId}` | String (JSON) | 游戏状态 (GameStateRecord) | 48h |
| `gomoku:room:{roomId}:turn` | String (JSON) | 回合倒计时锚点 (TurnAnchor) | 48h |

## 二、核心实体模型

### 2.1 RoomMeta (房间元信息)

**位置**: `domain/dto/RoomMeta.java`

**存储**: Redis String (JSON 序列化)

**字段说明**:

```java
public class RoomMeta {
    private String roomId;          // 房间ID
    private String gameId;          // 当前局游戏ID
    private String mode;            // 模式: "PVP" | "PVE"
    private String rule;            // 规则: "STANDARD" | "RENJU"
    private String aiPiece;         // AI执子: "X" | "O" | null (PVP时为null)
    private int currentIndex;       // 当前盘序号
    private int blackWins;          // 黑方胜场数
    private int whiteWins;          // 白方胜场数
    private int draws;              // 平局数
    private String ownerUserId;     // 房主用户ID
    private String ownerName;       // 房主昵称
    private long createdAt;         // 创建时间 (epoch millis)
    private String phase;           // 房间状态: "WAITING" | "PLAYING" | "ENDED"
}
```

**生命周期**:
- 创建: `newRoom()` 时创建
- 更新: 游戏状态变更、房间状态变更时更新
- 删除: `destroyRoom()` 时删除

### 2.2 SeatsBinding (座位绑定)

**位置**: `domain/dto/SeatsBinding.java`

**存储**: Redis String (JSON 序列化)

**字段说明**:

```java
public class SeatsBinding {
    private String seatXSessionId;              // 黑方(X)座位绑定的userId
    private String seatOSessionId;              // 白方(O)座位绑定的userId
    private Map<String, String> seatBySession; // userId -> "X"/"O" 映射
    private Map<String, Boolean> readyByUserId; // userId -> ready状态
}
```

**关键逻辑**:
- `seatXSessionId` / `seatOSessionId`: 存储实际占用座位的用户ID
- `seatBySession`: 反向映射，用于快速查找用户占用的座位
- `readyByUserId`: 准备状态，key为userId，value为true/false

### 2.3 Room (内存房间对象)

**位置**: `domain/model/Room.java`

**存储**: 内存 `ConcurrentHashMap<String, Room>`

**字段说明**:

```java
public class Room {
    private final String id;                    // 房间ID
    private final Mode mode;                    // 模式
    private final Rule rule;                    // 规则
    private final char aiPiece;                 // AI执子
    private final GomokuAI ai;                  // AI实例
    private final Series series;                // 系列赛信息
    private final Map<String, Character> seatBySession;  // sessionId -> side
    private final Map<String, Character> seatKeyToSeat;  // seatKey -> side
    private final Map<Character, String> seatToSessionId; // side -> sessionId
    private volatile String seatXSessionId;    // 黑方sessionId
    private volatile String seatOSessionId;     // 白方sessionId
}
```

**作用**:
- 作为运行时的缓存对象，提高访问速度
- 存储AI实例等不可序列化的对象
- 与Redis数据保持同步（通过 `room()` 方法懒加载）

## 三、核心服务实现

### 3.1 GomokuServiceImpl 核心方法

#### 3.1.1 创建房间 (`newRoom`)

**方法签名**:
```java
String newRoom(Mode mode, Character aiPiece, Rule rule, String ownerUserId, String ownerName)
```

**实现流程**:

```java
1. 参数处理与默认值
   ├── mode: null → PVE
   ├── rule: null → STANDARD
   └── aiPiece: null → WHITE (后手)

2. 生成ID
   ├── roomId: UUID.randomUUID()
   └── gameId: UUID.randomUUID()

3. 创建 RoomMeta → Redis
   ├── 设置基本信息 (mode, rule, aiPiece)
   ├── 设置房主信息 (ownerUserId, ownerName)
   ├── 初始化系列赛统计 (blackWins=0, whiteWins=0, draws=0, currentIndex=1)
   ├── 设置 phase = "WAITING"
   └── 设置 createdAt = System.currentTimeMillis()

4. 添加到房间索引 (ZSET)
   └── roomRepo.addRoomIndex(roomId, createdAt, TTL)

5. 记录 ongoing-game
   └── ongoingGameTracker.save(ownerUserId, OngoingGameInfo.gomoku(roomId))

6. 创建初始 GameStateRecord → Redis
   ├── 空棋盘 (225个'.')
   ├── current = "X" (黑先)
   ├── over = false
   └── step = 0

7. 初始化 SeatsBinding → Redis
   └── seatXSessionId = ownerUserId (房主绑定黑方)

8. 创建内存 Room 对象
   └── rooms.put(roomId, new Room(...))

9. 返回 roomId
```

**关键点**:
- ✅ 房主自动绑定黑方座位
- ✅ 房间状态初始化为 `WAITING`
- ✅ 所有数据写入Redis，保证持久化
- ❌ **问题**: 创建后没有广播事件，其他玩家无法感知新房间

#### 3.1.2 绑定座位 (`resolveAndBindSide`)

**方法签名**:
```java
char resolveAndBindSide(String roomId, String userId, Character wantSide)
```

**实现流程**:

```java
1. 获取房间信息
   └── Room r = room(roomId) (从内存或Redis加载)

2. 读取当前座位绑定
   └── SeatsBinding seats = roomRepo.getSeats(roomId)

3. 检查是否已绑定
   ├── 如果 userId == seatXSessionId → 返回 'X'
   └── 如果 userId == seatOSessionId → 返回 'O'

4. 根据模式分配座位
   ├── PVE模式:
   │   ├── 获取AI执子 (aiPiece)
   │   ├── 玩家执子 = 与AI相反的一方
   │   ├── 检查玩家座位是否被占用
   │   │   ├── 被其他人占用 → 抛异常
   │   │   └── 空闲或自己占用 → 绑定
   │   └── 返回玩家执子
   │
   └── PVP模式:
       ├── 如果 wantSide != null:
       │   ├── 检查意向座位是否空闲
       │   │   ├── 空闲 → 绑定
       │   │   └── 被占用 → 抛异常
       │   └── 返回意向座位
       │
       └── 如果 wantSide == null (自动分配):
           ├── 黑方空闲 → 绑定黑方
           ├── 白方空闲 → 绑定白方
           └── 都满 → 抛异常

5. 更新 Redis SeatsBinding
   ├── 根据分配的side更新 seatXSessionId 或 seatOSessionId
   ├── 更新 seatBySession 映射
   └── roomRepo.saveSeats(roomId, seats, TTL)

6. 更新内存 Room 对象
   ├── r.getSeatBySession().put(userId, side)
   ├── r.setSeatXSessionId(userId) 或 r.setSeatOSessionId(userId)
   └── 同步到内存

7. 返回分配的座位 ('X' 或 'O')
```

**关键点**:
- ✅ 支持PVE和PVP两种模式的座位分配
- ✅ 支持意向座位和自动分配
- ✅ 同时更新Redis和内存
- ❌ **问题**: 绑定后没有广播座位更新事件，房间内其他玩家无法感知

#### 3.1.3 加入房间 (`joinRoom` - HTTP接口)

**位置**: `GomokuRestController.joinRoom()`

**实现流程**:

```java
1. 验证房主身份
   ├── 获取 ownerUserId
   └── 如果是房主 → 返回409错误

2. 绑定座位
   └── char side = svc.resolveAndBindSide(roomId, userId, null)

3. 记录 ongoing-game
   └── ongoingGameTracker.save(userId, OngoingGameInfo.gomoku(roomId))

4. 返回分配的座位
   └── return JoinRoomResponse(side)
```

**关键点**:
- ✅ 防止房主加入自己的房间
- ✅ 自动分配座位
- ❌ **严重问题**: 没有广播 `PLAYER_JOINED` 事件，房间内其他玩家无法感知新玩家加入
- ❌ **严重问题**: 没有发送 `SNAPSHOT` 事件更新座位信息

#### 3.1.4 准备状态管理

##### toggleReady (切换准备状态)

**方法签名**:
```java
boolean toggleReady(String roomId, String userId)
```

**实现流程**:

```java
1. 读取 SeatsBinding
   └── SeatsBinding seats = roomRepo.getSeats(roomId)

2. 获取当前准备状态
   └── boolean current = seats.getReadyByUserId().getOrDefault(userId, false)

3. 切换状态
   └── seats.getReadyByUserId().put(userId, !current)

4. 保存到 Redis
   └── roomRepo.saveSeats(roomId, seats, TTL)

5. 返回新状态
   └── return !current
```

**关键点**:
- ✅ 状态切换逻辑简单清晰
- ✅ 通过WebSocket广播 `READY_STATUS` 事件 (在 `GomokuWsController` 中)

##### getAllReadyStatus (获取所有准备状态)

**方法签名**:
```java
Map<String, Boolean> getAllReadyStatus(String roomId)
```

**实现**:
```java
SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
return seats.getReadyByUserId() != null 
    ? seats.getReadyByUserId() 
    : Collections.emptyMap();
```

#### 3.1.5 开始游戏 (`startGame`)

**方法签名**:
```java
void startGame(String roomId, String userId)
```

**实现流程**:

```java
1. 验证房主身份
   ├── RoomMeta meta = roomRepo.getRoomMeta(roomId)
   └── if (userId != meta.getOwnerUserId()) → 抛异常

2. 验证房间状态
   ├── RoomPhase phase = getRoomPhase(roomId)
   └── if (phase != WAITING) → 抛异常

3. 验证准备状态
   ├── 读取 SeatsBinding
   ├── 获取所有玩家 (seatXSessionId, seatOSessionId)
   ├── PVE模式: 只需房主准备
   └── PVP模式: 需要所有玩家都准备

4. 检查游戏状态
   ├── GomokuState currentState = getState(roomId)
   └── if (currentState.over()) → 创建新局 (newGame)

5. 切换房间状态
   └── setRoomPhase(roomId, RoomPhase.PLAYING)

6. 返回 (状态变更通过WebSocket广播)
```

**关键点**:
- ✅ 完整的验证逻辑
- ✅ 支持PVE和PVP两种模式
- ✅ 自动创建新局（如果上一局已结束）
- ✅ 通过WebSocket广播 `ROOM_STATUS` 事件

#### 3.1.6 离开房间 (`leaveRoom`)

**方法签名**:
```java
LeaveResult leaveRoom(String roomId, String userId)
```

**实现流程**:

```java
1. 读取房间信息
   ├── RoomMeta meta = roomRepo.getRoomMeta(roomId)
   └── Mode mode = Mode.valueOf(meta.getMode())

2. PVE模式处理
   ├── 直接销毁房间
   ├── destroyRoom(roomId)
   ├── 清理 ongoing-game
   └── 返回 LeaveResult(roomDestroyed=true, ...)

3. PVP模式处理
   ├── 读取 SeatsBinding
   ├── 判断离开者座位 (isX / isO)
   │
   ├── 如果不在房间
   │   └── 返回 LeaveResult(roomDestroyed=false, ...)
   │
   ├── 获取对手信息
   │   └── opponentUserId = isX ? seatOSessionId : seatXSessionId
   │
   ├── 如果对手不存在 (房间只剩离开者)
   │   ├── destroyRoom(roomId)
   │   └── 返回 LeaveResult(roomDestroyed=true, ...)
   │
   └── 如果对手存在
       ├── 解绑离开者的座位
       │   ├── seats.setSeatXSessionId(null) 或 setSeatOSessionId(null)
       │   └── seats.getSeatBySession().remove(userId)
       │
       ├── 更新内存 Room
       │   └── local.getSeatBySession().remove(userId)
       │
       ├── 房主交接 (如果离开者是房主)
       │   ├── newOwner = opponentUserId
       │   └── meta.setOwnerUserId(newOwner)
       │
       ├── 保存到 Redis
       │   ├── roomRepo.saveSeats(roomId, seats, TTL)
       │   └── roomRepo.saveRoomMeta(roomId, meta, TTL)
       │
       └── 返回 LeaveResult(roomDestroyed=false, newOwner, freedSeat)
```

**关键点**:
- ✅ PVE模式直接销毁房间
- ✅ PVP模式支持房主交接
- ✅ 正确清理座位绑定
- ❌ **问题**: 离开后没有广播事件，其他玩家无法感知

#### 3.1.7 房间快照 (`snapshot`)

**方法签名**:
```java
GomokuSnapshot snapshot(String roomId)
```

**实现流程**:

```java
1. 读取 RoomMeta
   └── RoomMeta meta = roomRepo.getRoomMeta(roomId)

2. 读取 SeatsBinding
   ├── SeatsBinding seats = roomRepo.getSeats(roomId)
   ├── seatXOccupied = seats.getSeatXSessionId() != null
   └── seatOOccupied = seats.getSeatOSessionId() != null

3. 读取 GameStateRecord
   ├── String gameId = meta.getGameId()
   └── GameStateRecord rec = gameRepo.get(roomId, gameId)

4. 读取 TurnAnchor (倒计时)
   └── TurnAnchor anchor = turnRepo.get(roomId)

5. 读取 Series 统计
   └── SeriesView sv = getSeries(roomId)

6. 构建棋盘 (String → char[][])
   └── 解析 rec.getBoard() (225个字符)

7. 构建 GomokuSnapshot
   └── new GomokuSnapshot(
       roomId, seatXOccupied, seatOOccupied,
       mode, aiSide, rule, phase,
       boardSize, cells, sideToMove,
       turnSeq, deadlineEpochMs,
       round, scoreX, scoreO, outcome,
       readyStatus
   )
```

**关键点**:
- ✅ 聚合所有房间状态信息
- ✅ 返回不可变快照对象
- ✅ 用于WebSocket FullSync

## 四、接口层实现

### 4.1 HTTP REST 接口

#### 4.1.1 GomokuRestController

**路径**: `/api/gomoku`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/new` | 创建房间 |
| POST | `/{roomId}/place` | 落子 (HTTP，已废弃，主要用WebSocket) |
| GET | `/{roomId}/suggest` | AI建议 |
| POST | `/rooms/{roomId}/join` | 加入房间 |
| POST | `/rooms/{roomId}/leave` | 离开房间 |

#### 4.1.2 RoomListController

**路径**: `/api/gomoku/rooms`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/?cursor=&limit=` | 获取房间列表 (分页) |

**实现**:
- 使用 Redis ZSET (`gomoku:rooms:index`) 按创建时间倒序
- 支持游标分页 (cursor-based pagination)
- 过滤已删除的房间

### 4.2 WebSocket 接口

#### 4.2.1 GomokuWsController

**路径**: `/app/gomoku.*`

| 消息映射 | 说明 |
|---------|------|
| `/gomoku.place` | 落子 |
| `/gomoku.resign` | 认输 |
| `/gomoku.ready` | 准备/取消准备 |
| `/gomoku.start` | 开始游戏 |
| `/gomoku.restart` | 重新开始 |

**广播机制**:

```java
// 广播到房间内所有玩家
messaging.convertAndSend("/topic/room." + roomId, event);

// 发送给特定用户
messaging.convertAndSendToUser(userId, "/queue/gomoku.seat", seatKey);
```

**事件类型**:

| 事件类型 | 触发时机 | 包含数据 |
|---------|---------|---------|
| `STATE` | 落子后 | state, series |
| `SNAPSHOT` | 落子后、状态变更后 | 完整快照 |
| `READY_STATUS` | 准备状态变更 | Map<userId, ready> |
| `ROOM_STATUS` | 房间状态变更 | {phase: "WAITING"/"PLAYING"/"ENDED"} |
| `ERROR` | 错误发生 | 错误消息 |

#### 4.2.2 GomokuResumeController

**路径**: `/app/gomoku.resume`

**用途**: 页面刷新/重连时恢复房间状态

**流程**:

```java
1. 接收 ResumeCmd {roomId, seatKey}

2. 尝试绑定 seatKey
   └── Character bound = gomoku.bindBySeatKey(roomId, seatKey, userId)

3. 生成完整快照
   └── GomokuSnapshot snap = gomoku.snapshot(roomId)

4. 构建 FullSync DTO
   └── FullSync.builder()
       .roomId(snap.roomId)
       .seats(new Seats(snap.seatXOccupied, snap.seatOOccupied, 0))
       .myRole(bound != null ? "PLAYER" : "VIEWER")
       .mySide(bound)
       .mode(snap.mode)
       .aiSide(snap.aiSide)
       .rule(snap.rule)
       .phase(snap.phase)
       .seriesView(...)
       .board(...)
       .readyStatus(snap.readyStatus)
       .build()

5. 发送到 /user/queue/gomoku.full
```

## 五、状态管理流程

### 5.1 房间状态流转

```
CREATED (隐式)
  ↓ newRoom() 完成
WAITING
  ↓ startGame() (所有玩家已准备)
PLAYING
  ↓ 游戏结束 (胜负/认输/超时)
ENDED
  ↓ restart() 或 所有玩家离开
WAITING 或 DESTROYED
```

### 5.2 状态存储位置

| 状态 | 存储位置 | 字段 |
|------|---------|------|
| phase | RoomMeta.phase | "WAITING" / "PLAYING" / "ENDED" |
| 座位绑定 | SeatsBinding | seatXSessionId, seatOSessionId |
| 准备状态 | SeatsBinding.readyByUserId | Map<userId, ready> |
| 游戏状态 | GameStateRecord | board, current, over, winner |

### 5.3 状态同步机制

**Redis → 内存**:
- 懒加载: `room(roomId)` 方法从Redis加载到内存
- 回灌座位: 从 `SeatsBinding` 同步到内存 `Room` 对象

**内存 → Redis**:
- 每次状态变更都写Redis
- 通过 `roomRepo.save*()` 方法持久化

**服务端 → 客户端**:
- WebSocket 广播 `STATE` / `SNAPSHOT` 事件
- 客户端通过 `FullSync` 获取完整状态

## 六、存在的问题分析

### 6.1 严重问题

#### 问题1: 玩家加入后没有广播事件

**位置**: `GomokuRestController.joinRoom()`

**现象**:
- 玩家B通过HTTP接口加入房间
- 座位已绑定到Redis
- 但房间内其他玩家（玩家A）无法感知

**影响**:
- 玩家A看不到玩家B已加入
- 玩家B也看不到玩家A（因为前端没有处理座位信息）

**解决方案**:
```java
// 在 joinRoom() 方法中，绑定座位后添加:
// 1. 广播 PLAYER_JOINED 事件
// 2. 广播 SNAPSHOT 事件更新座位信息
```

#### 问题2: 前端没有处理座位信息

**位置**: `useGomokuGame.js` 的 `handleFullSync()`

**现象**:
- `GomokuSnapshot` 包含 `seatXOccupied` 和 `seatOOccupied`
- `FullSync` 包含 `seats` 对象
- 但前端没有提取和处理这些信息

**影响**:
- 无法显示对手信息
- 无法判断房间是否已满

**解决方案**:
```javascript
// 在 handleFullSync 中添加:
if (snap.seats) {
  // 更新座位信息
  // 根据 seatXOccupied / seatOOccupied 更新对手显示
}
```

#### 问题3: 离开房间后没有广播事件

**位置**: `GomokuServiceImpl.leaveRoom()`

**现象**:
- 玩家离开后，座位已解绑
- 但房间内其他玩家无法感知

**影响**:
- 其他玩家看不到玩家已离开
- 座位信息不同步

### 6.2 设计问题

#### 问题4: 状态转换没有统一管理

**现象**:
- 状态转换逻辑分散在各个方法中
- 没有统一的状态机管理
- 状态转换验证不完整

**影响**:
- 容易出现状态不一致
- 难以维护和扩展

#### 问题5: 事件系统不完整

**现象**:
- 只有部分操作会广播事件
- 事件类型不完整（缺少 `PLAYER_JOINED`, `PLAYER_LEFT` 等）
- 事件发布逻辑分散

**影响**:
- 客户端无法实时感知所有状态变更
- 需要轮询或刷新才能获取最新状态

#### 问题6: 内存和Redis数据可能不一致

**现象**:
- 内存 `Room` 对象作为缓存
- 但更新时可能只更新Redis，忘记更新内存
- 或只更新内存，忘记更新Redis

**影响**:
- 数据不一致
- 可能导致业务逻辑错误

### 6.3 代码结构问题

#### 问题7: 职责不清晰

**现象**:
- `GomokuServiceImpl` 承担了太多职责
  - 房间管理
  - 座位管理
  - 游戏逻辑
  - 状态管理
  - 事件广播

**影响**:
- 代码臃肿
- 难以测试
- 难以维护

#### 问题8: 缺少抽象层

**现象**:
- 直接操作Redis和内存
- 没有统一的状态管理抽象
- 没有事件发布抽象

**影响**:
- 耦合度高
- 难以扩展

## 七、数据流分析

### 7.1 创建房间数据流

```
HTTP POST /api/gomoku/new
  ↓
GomokuRestController.newRoom()
  ↓
GomokuServiceImpl.newRoom()
  ├── 生成 roomId, gameId
  ├── 创建 RoomMeta → Redis
  ├── 创建 SeatsBinding → Redis (房主绑定黑方)
  ├── 创建 GameStateRecord → Redis
  ├── 添加到 RoomIndex (ZSET) → Redis
  ├── 创建内存 Room 对象
  └── 记录 ongoing-game
  ↓
返回 roomId
```

**问题**: 没有广播 `ROOM_CREATED` 事件

### 7.2 加入房间数据流

```
HTTP POST /api/gomoku/rooms/{roomId}/join
  ↓
GomokuRestController.joinRoom()
  ├── 验证房主身份
  ├── GomokuServiceImpl.resolveAndBindSide()
  │   ├── 读取 SeatsBinding
  │   ├── 分配座位
  │   ├── 更新 SeatsBinding → Redis
  │   └── 更新内存 Room
  ├── 记录 ongoing-game
  └── 返回分配的座位
```

**问题**: 
- 没有广播 `PLAYER_JOINED` 事件
- 没有广播 `SNAPSHOT` 更新座位信息

### 7.3 准备状态变更数据流

```
WebSocket /app/gomoku.ready
  ↓
GomokuWsController.ready()
  ├── GomokuServiceImpl.toggleReady()
  │   ├── 读取 SeatsBinding
  │   ├── 切换准备状态
  │   └── 保存 SeatsBinding → Redis
  └── sendReadyStatusUpdate()
      └── 广播 READY_STATUS 事件
```

**正常**: 有事件广播

### 7.4 开始游戏数据流

```
WebSocket /app/gomoku.start
  ↓
GomokuWsController.start()
  ├── GomokuServiceImpl.startGame()
  │   ├── 验证房主身份
  │   ├── 验证房间状态
  │   ├── 验证准备状态
  │   ├── 创建新局 (如果需要)
  │   └── 切换 phase = PLAYING
  └── sendRoomStatusUpdate()
      └── 广播 ROOM_STATUS 事件
```

**正常**: 有事件广播

### 7.5 游戏状态同步数据流

```
WebSocket /app/gomoku.place
  ↓
GomokuWsController.place()
  ├── GomokuServiceImpl.place()
  │   └── 更新游戏状态
  └── sendState()
      ├── 广播 STATE 事件
      └── 广播 SNAPSHOT 事件
```

**正常**: 有事件广播

## 八、代码统计

### 8.1 核心类行数统计

| 类名 | 行数 | 职责 |
|------|------|------|
| `GomokuServiceImpl` | ~1078 | 核心业务逻辑 |
| `GomokuWsController` | ~477 | WebSocket处理 |
| `GomokuRestController` | ~125 | HTTP REST接口 |
| `RedisRoomRepository` | ~227 | Redis持久化 |
| `Room` | ~84 | 内存房间对象 |
| `RoomMeta` | ~43 | 房间元信息DTO |
| `SeatsBinding` | ~32 | 座位绑定DTO |

### 8.2 方法统计

**GomokuServiceImpl 主要方法**:
- `newRoom()`: 创建房间
- `resolveAndBindSide()`: 绑定座位 (~96行)
- `place()`: 落子
- `resign()`: 认输
- `newGame()`: 创建新局
- `snapshot()`: 生成快照 (~100行)
- `toggleReady()`: 切换准备状态
- `startGame()`: 开始游戏
- `leaveRoom()`: 离开房间 (~80行)
- `getRoomPhase()` / `setRoomPhase()`: 房间状态管理

## 九、总结

### 9.1 优点

1. **数据持久化完善**: Redis作为单一数据源，数据持久化完整
2. **支持断线重连**: 通过seatKey机制支持刷新重入
3. **状态快照机制**: `snapshot()` 方法提供完整状态快照
4. **WebSocket实时通信**: 大部分操作都有事件广播

### 9.2 缺点

1. **事件系统不完整**: 缺少关键事件（PLAYER_JOINED, PLAYER_LEFT）
2. **状态管理分散**: 没有统一的状态机管理
3. **职责过重**: `GomokuServiceImpl` 承担太多职责
4. **数据同步问题**: 内存和Redis可能不一致
5. **前端处理缺失**: 前端没有处理座位信息

### 9.3 改进建议

1. **实现完整的事件系统**: 所有状态变更都通过事件通知
2. **引入状态机管理**: 统一管理房间状态转换
3. **职责分离**: 拆分 `GomokuServiceImpl`，引入 `RoomService`
4. **统一数据同步**: 确保内存和Redis数据一致性
5. **完善前端处理**: 前端正确处理座位信息和所有事件类型

