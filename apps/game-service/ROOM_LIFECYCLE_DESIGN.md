# 房间生命周期管理设计文档

## 一、生命周期状态机

### 1.1 房间状态定义

```
CREATED (创建中)
  ↓
WAITING (等待玩家/准备)
  ↓
PLAYING (对局中)
  ↓
ENDED (对局结束)
  ↓
[可循环] → WAITING (重新开始)
  ↓
DESTROYED (已销毁)
```

### 1.2 详细状态说明

| 状态 | 说明 | 允许的操作 | 进入条件 | 退出条件 |
|------|------|-----------|----------|----------|
| **CREATED** | 房间刚创建，初始化中 | 无 | 调用 `newRoom()` | 初始化完成 → WAITING |
| **WAITING** | 等待玩家加入/准备 | 加入房间、准备/取消准备、开始游戏 | CREATED 完成初始化 | 房主点击"开始游戏" → PLAYING |
| **PLAYING** | 对局进行中 | 落子、认输、倒计时 | WAITING + 所有玩家已准备 | 游戏结束 → ENDED |
| **ENDED** | 对局结束 | 重新开始、离开房间 | PLAYING + 游戏结束 | 重新开始 → WAITING 或 离开 → DESTROYED |
| **DESTROYED** | 房间已销毁 | 无 | 所有玩家离开 | 不可恢复 |

### 1.3 状态转换规则

```java
// 状态转换矩阵
CREATED → WAITING:    自动（初始化完成）
WAITING → PLAYING:    房主调用 startGame() + 所有玩家已准备
PLAYING → ENDED:      游戏结束（胜负/认输/超时）
ENDED → WAITING:      房主调用 restart() + 创建新局
ENDED → DESTROYED:    所有玩家离开
WAITING → DESTROYED:  所有玩家离开
```

## 二、房间实体模型

### 2.1 核心实体

```
Room (房间)
├── RoomMeta (元信息)
│   ├── roomId
│   ├── ownerUserId / ownerName
│   ├── mode (PVP/PVE)
│   ├── rule (STANDARD/RENJU)
│   ├── phase (WAITING/PLAYING/ENDED)
│   ├── createdAt
│   └── Series (系列赛统计)
│
├── SeatsBinding (座位绑定)
│   ├── seatXSessionId (黑方用户ID)
│   ├── seatOSessionId (白方用户ID)
│   ├── seatBySession (userId -> side)
│   └── readyByUserId (userId -> ready)
│
├── GameState (当前对局状态)
│   ├── gameId
│   ├── board
│   ├── current (轮到谁)
│   ├── over / winner
│   └── lastMove
│
└── TurnClock (回合倒计时)
    ├── deadlineEpochMs
    ├── side
    └── turnSeq
```

## 三、事件驱动架构

### 3.1 房间事件类型

```java
public enum RoomEventType {
    // 房间生命周期事件
    ROOM_CREATED,      // 房间创建完成
    ROOM_DESTROYED,    // 房间销毁
    
    // 玩家事件
    PLAYER_JOINED,     // 玩家加入房间
    PLAYER_LEFT,       // 玩家离开房间
    PLAYER_READY,      // 玩家准备
    PLAYER_UNREADY,    // 玩家取消准备
    
    // 游戏事件
    GAME_STARTED,      // 游戏开始
    GAME_ENDED,        // 游戏结束
    GAME_RESTARTED,    // 游戏重新开始
    
    // 对局事件
    MOVE_PLACED,       // 落子
    RESIGNED,          // 认输
    TIMEOUT,           // 超时
    
    // 状态同步事件
    STATE_UPDATED,     // 状态更新
    SNAPSHOT,          // 完整快照
    SEATS_UPDATED,     // 座位更新
}
```

### 3.2 事件广播机制

```java
// 所有房间事件都通过 WebSocket 广播到房间内所有玩家
/topic/room.{roomId} → 房间内所有玩家
/user/queue/gomoku.full → 单个玩家（FullSync）
/user/queue/gomoku.seat → 单个玩家（SeatKey）
```

## 四、代码结构设计

### 4.1 包结构

```
games/gomoku/
├── domain/                    # 领域模型
│   ├── enums/
│   │   ├── RoomPhase.java     # 房间状态枚举
│   │   ├── RoomEventType.java # 事件类型枚举
│   │   └── ...
│   ├── model/
│   │   ├── Room.java          # 房间实体（内存）
│   │   ├── RoomState.java     # 房间状态（不可变）
│   │   └── ...
│   ├── dto/
│   │   ├── RoomMeta.java      # 房间元信息（Redis）
│   │   ├── SeatsBinding.java  # 座位绑定（Redis）
│   │   └── ...
│   └── events/
│       ├── RoomEvent.java     # 房间事件基类
│       ├── PlayerJoinedEvent.java
│       ├── GameStartedEvent.java
│       └── ...
│
├── application/               # 应用服务层
│   ├── RoomLifecycleManager.java    # 房间生命周期管理器（核心）
│   ├── RoomEventPublisher.java      # 事件发布器
│   ├── RoomStateSynchronizer.java  # 状态同步器
│   └── TurnClockCoordinator.java   # 倒计时协调器
│
├── service/                   # 业务服务层
│   ├── GomokuService.java     # 游戏服务接口
│   ├── RoomService.java       # 房间服务接口（新增）
│   └── impl/
│       ├── GomokuServiceImpl.java
│       └── RoomServiceImpl.java   # 房间服务实现（新增）
│
└── interfaces/                # 接口层
    ├── http/
    │   ├── RoomController.java     # 房间HTTP接口
    │   └── ...
    └── ws/
        ├── RoomEventController.java # 房间事件WebSocket
        └── ...
```

### 4.2 核心类职责

#### RoomLifecycleManager（房间生命周期管理器）
```java
/**
 * 职责：
 * 1. 管理房间状态转换
 * 2. 验证状态转换的合法性
 * 3. 触发状态转换事件
 * 4. 协调各个组件（RoomService, EventPublisher, StateSynchronizer）
 */
public class RoomLifecycleManager {
    // 状态转换方法
    void transitionToWaiting(String roomId);
    void transitionToPlaying(String roomId, String userId);
    void transitionToEnded(String roomId);
    void transitionToDestroyed(String roomId);
    
    // 状态验证
    boolean canTransition(String roomId, RoomPhase from, RoomPhase to);
    
    // 事件触发
    void publishStateChange(String roomId, RoomPhase oldPhase, RoomPhase newPhase);
}
```

#### RoomService（房间服务）
```java
/**
 * 职责：
 * 1. 房间的CRUD操作
 * 2. 玩家加入/离开
 * 3. 座位绑定管理
 * 4. 准备状态管理
 */
public interface RoomService {
    // 房间创建
    RoomState createRoom(CreateRoomRequest request);
    
    // 玩家操作
    JoinResult joinRoom(String roomId, String userId);
    LeaveResult leaveRoom(String roomId, String userId);
    
    // 座位管理
    char bindSeat(String roomId, String userId, Character preferredSide);
    void unbindSeat(String roomId, String userId);
    
    // 准备状态
    boolean toggleReady(String roomId, String userId);
    Map<String, Boolean> getReadyStatus(String roomId);
    
    // 状态查询
    RoomState getRoomState(String roomId);
    boolean isRoomActive(String roomId);
}
```

#### RoomEventPublisher（事件发布器）
```java
/**
 * 职责：
 * 1. 发布房间事件到WebSocket
 * 2. 确保事件顺序性
 * 3. 事件持久化（可选）
 */
public class RoomEventPublisher {
    void publish(RoomEvent event);
    void broadcastToRoom(String roomId, RoomEvent event);
    void sendToUser(String userId, RoomEvent event);
}
```

#### RoomStateSynchronizer（状态同步器）
```java
/**
 * 职责：
 * 1. 同步房间状态到Redis
 * 2. 生成房间快照（Snapshot）
 * 3. 广播状态更新
 */
public class RoomStateSynchronizer {
    void syncRoomState(String roomId);
    GomokuSnapshot createSnapshot(String roomId);
    void broadcastSnapshot(String roomId);
}
```

## 五、关键流程设计

### 5.1 创建房间流程

```
1. HTTP: POST /api/gomoku/new
   ↓
2. RoomService.createRoom()
   ├── 生成 roomId, gameId
   ├── 创建 RoomMeta → Redis
   ├── 初始化 SeatsBinding → Redis
   ├── 创建空 GameState → Redis
   ├── 创建内存 Room 对象
   ├── 绑定房主座位（黑方）
   ├── 设置 phase = WAITING
   └── 记录 ongoing-game
   ↓
3. RoomLifecycleManager.transitionToWaiting()
   ├── 验证状态
   ├── 更新 phase
   └── 触发 ROOM_CREATED 事件
   ↓
4. RoomEventPublisher.publish()
   └── 广播到 /topic/room.{roomId}
   ↓
5. RoomStateSynchronizer.syncRoomState()
   └── 同步到 Redis
```

### 5.2 玩家加入房间流程

```
1. HTTP: POST /api/gomoku/rooms/{roomId}/join
   ↓
2. RoomService.joinRoom()
   ├── 验证房间状态（必须是 WAITING）
   ├── 验证是否已满
   ├── 调用 bindSeat() 分配座位
   └── 记录 ongoing-game
   ↓
3. RoomService.bindSeat()
   ├── 读取 SeatsBinding
   ├── 分配座位（X 或 O）
   ├── 更新 SeatsBinding → Redis
   └── 更新内存 Room
   ↓
4. RoomEventPublisher.publish(PlayerJoinedEvent)
   ├── 广播 PLAYER_JOINED 事件
   └── 包含：userId, side, seats 信息
   ↓
5. RoomStateSynchronizer.broadcastSnapshot()
   └── 广播完整快照，更新所有玩家的座位信息
```

### 5.3 开始游戏流程

```
1. WebSocket: /app/gomoku.start
   ↓
2. RoomService.startGame()
   ├── 验证房主身份
   ├── 验证房间状态（必须是 WAITING）
   ├── 验证准备状态（所有玩家已准备）
   └── 调用 RoomLifecycleManager.transitionToPlaying()
   ↓
3. RoomLifecycleManager.transitionToPlaying()
   ├── 验证状态转换合法性
   ├── 更新 phase = PLAYING
   ├── 如果游戏已结束，创建新局（newGame）
   └── 触发 GAME_STARTED 事件
   ↓
4. RoomEventPublisher.publish(GameStartedEvent)
   └── 广播到房间内所有玩家
   ↓
5. TurnClockCoordinator.syncFromState()
   └── 启动倒计时
```

### 5.4 游戏结束流程

```
1. 游戏结束（胜负/认输/超时）
   ↓
2. GomokuService.place() / resign() / timeout()
   └── 检测游戏结束
   ↓
3. RoomLifecycleManager.transitionToEnded()
   ├── 更新 phase = ENDED
   ├── 重置准备状态
   └── 触发 GAME_ENDED 事件
   ↓
4. RoomEventPublisher.publish(GameEndedEvent)
   └── 广播游戏结果
   ↓
5. TurnClockCoordinator.stop()
   └── 停止倒计时
```

## 六、状态一致性保证

### 6.1 状态同步策略

1. **Redis 作为单一数据源（Single Source of Truth）**
   - 所有状态变更先写 Redis
   - 内存 Room 对象仅作为缓存

2. **事件驱动同步**
   - 状态变更 → 发布事件 → 同步到 Redis → 广播到客户端

3. **幂等性保证**
   - 所有操作支持重复执行
   - 状态转换前验证当前状态

### 6.2 并发控制

```java
// 使用 Redis 分布式锁
String lockKey = "lock:room:" + roomId;
if (redisLock.tryLock(lockKey, 5, TimeUnit.SECONDS)) {
    try {
        // 执行状态转换
    } finally {
        redisLock.unlock(lockKey);
    }
}
```

## 七、实现优先级

### Phase 1: 核心生命周期管理
1. 创建 `RoomLifecycleManager`
2. 重构 `RoomService` 接口
3. 实现状态转换逻辑
4. 添加状态验证

### Phase 2: 事件系统
1. 定义 `RoomEvent` 体系
2. 实现 `RoomEventPublisher`
3. 集成到现有 WebSocket 广播

### Phase 3: 状态同步
1. 实现 `RoomStateSynchronizer`
2. 统一状态同步入口
3. 确保 Redis 和内存一致性

### Phase 4: 完善和优化
1. 添加分布式锁
2. 事件持久化（可选）
3. 性能优化
4. 监控和日志

## 八、代码示例

### 8.1 RoomLifecycleManager 示例

```java
@Service
@RequiredArgsConstructor
public class RoomLifecycleManager {
    private final RoomRepository roomRepository;
    private final RoomEventPublisher eventPublisher;
    private final RoomStateSynchronizer stateSynchronizer;
    
    public void transitionToPlaying(String roomId, String userId) {
        RoomMeta meta = roomRepository.getRoomMeta(roomId)
            .orElseThrow(() -> new IllegalStateException("房间不存在"));
        
        RoomPhase currentPhase = RoomPhase.valueOf(meta.getPhase());
        if (currentPhase != RoomPhase.WAITING) {
            throw new IllegalStateException(
                "房间状态必须是 WAITING，当前状态: " + currentPhase);
        }
        
        // 验证准备状态
        SeatsBinding seats = roomRepository.getSeats(roomId)
            .orElseGet(SeatsBinding::new);
        // ... 验证逻辑 ...
        
        // 执行状态转换
        meta.setPhase(RoomPhase.PLAYING.name());
        roomRepository.saveRoomMeta(roomId, meta, ROOM_TTL);
        
        // 发布事件
        eventPublisher.publish(new GameStartedEvent(roomId, userId));
        
        // 同步状态
        stateSynchronizer.syncRoomState(roomId);
    }
}
```

## 九、总结

这套设计提供了：
1. **清晰的状态机**：明确定义房间的所有状态和转换规则
2. **事件驱动**：所有状态变更通过事件通知，解耦组件
3. **职责分离**：每个类有明确的单一职责
4. **状态一致性**：Redis 作为单一数据源，保证一致性
5. **可扩展性**：易于添加新的事件类型和状态转换

