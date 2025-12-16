## Countdown Clock Function Description

This is my designed countdown system. Main goals:
- Decouple countdown scheduling from specific business, make it a reusable generic engine
- Business layer (e.g., Gomoku) only cares about: when to start/stop, how to show per second, how to handle expiry

---

## I. Layers and Responsibilities

### 1.1 Generic Scheduling Engine (infrastructure layer)

Package: `com.gamehub.gameservice.clock.scheduler`

Interface `CountdownScheduler`, implementation `CountdownSchedulerImpl`.

Engine responsibilities:
- Manage countdown of key: `startOrResume`, `stop`, `restoreAllActive`
- Trigger `TickListener#onTick(key, owner, deadlineMs, left)` every second
- Trigger `TimeoutHandler#onTimeout(key, owner, version)` on expiry
- Persist state to Redis (`countdown:{key}`), use SETNX holder lock (`countdown:holder:{key}`) to ensure only one node handles timeout
- Use injected `ScheduledThreadPoolExecutor` for periodic scheduling

It does no business logic (broadcast, forfeit, etc.); upper coordinator handles that.

Key code:
```java
// CountdownScheduler interface
public interface CountdownScheduler {
    interface TickListener {
        void onTick(String key, String owner, long deadlineEpochMs, long remainingSeconds);
    }
    
    interface TimeoutHandler {
        void onTimeout(String key, String owner, String version);
    }
    
    void setTickListener(TickListener listener);
    void startOrResume(String key, String owner, long deadlineEpochMs, String version, TimeoutHandler onTimeout);
    void stop(String key);
    int restoreAllActive(TimeoutHandler onTimeout);
}
```

**Implementation details** (`CountdownSchedulerImpl`):
```java
// start or resume countdown
@Override
public void startOrResume(String key, String owner, long deadlineEpochMs, String version, TimeoutHandler onTimeout) {
    stop(key);  // prevent duplicate tasks
    CountdownState state = new CountdownState(key, owner, version, deadlineEpochMs);
    saveState(state);  // persist to Redis
    
    long remainMs = state.deadlineEpochMs - System.currentTimeMillis();
    if (remainMs <= 0) {
        // expired: try timeout immediately
        if (tryAcquireHolder(key)) {
            safeTimeout(onTimeout, state);
        }
        return;
    }
    
    fireTick(state);  // immediate first TICK, better UX
    ScheduledFuture<?> fut = scheduler.scheduleAtFixedRate(
            () -> tickTask(state, onTimeout), 1, 1, TimeUnit.SECONDS);
    activeTasks.put(key, fut);
}

// stop countdown (cleanup Redis state and holder lock)
@Override
public void stop(String key) {
    ScheduledFuture<?> f = activeTasks.remove(key);
    if (f != null) f.cancel(false);
    // clean Redis state and holder to avoid mistaken restore
    try {
        redis.delete(stateKey(key));
        redis.delete(holderKey(key));
    } catch (Exception ignore) {}
}

// restore all active countdowns (uses redis.keys, blocking risk)
@Override
public int restoreAllActive(TimeoutHandler onTimeout) {
    Set<String> keys = redis.keys(stateKey("*"));  // ⚠️ should switch to SCAN
    // ... restore logic
}
```

### 1.2 Business Coordination Layer (application layer)

Package: `com.gamehub.gameservice.games.gomoku.application`

Class: `TurnClockCoordinator`

Coordinator responsibilities:
- From `GomokuState` compute generic params and drive engine:
  - key = `"gomoku:"+roomId`
  - owner = side to move (`"X"` or `"O"`)
  - version = current `gameId`
  - deadlineMs = now + turn seconds
- Listen engine `tick`, convert to room `TICK` event and broadcast
- Listen engine `timeout`, call `gomokuService.resign` to forfeit, then broadcast `TIMEOUT/STATE/SNAPSHOT`
- Handle PVE `aiTimed=false` no-timer rule, and stop timer on end

Key code:
```java
@Component
@Lazy
@RequiredArgsConstructor
public class TurnClockCoordinator {
    private final CountdownScheduler scheduler;
    private final @Lazy GomokuService gomokuService;
    private final SimpMessagingTemplate messaging;
    private final GameStateRepository gameStateRepository;
    
    @Value("${gomoku.turn.seconds:30}")
    private int turnSeconds;
    
    @Value("${gomoku.turn.aiTimed:false}")
    private boolean aiTimed;
    
    // register TICK listener and restore active tasks on app ready
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // per-second TICK forwarding
        scheduler.setTickListener((key, owner, deadlineMs, left) -> {
            String roomId = extractRoomId(key);
            BroadcastEvent tick = new BroadcastEvent();
            tick.setRoomId(roomId);
            tick.setType("TICK");
            tick.setPayload(Map.of(
                    "left", (int) left,
                    "side", owner,
                    "deadlineEpochMs", deadlineMs
            ));
            messaging.convertAndSend(topic(roomId), tick);
        });
        
        // restore unexpired countdowns
        int restored = scheduler.restoreAllActive((key, owner, version) -> handleTimeout(key, owner));
        log.info("Coordinator ready: restored {} countdown tasks", restored);
    }
    
    // drive countdown by latest state
    public void syncFromState(String roomId, GomokuState state) {
        if (state.over()) { stop(roomId); return; }
        
        RoomPhase phase = gomokuService.getRoomPhase(roomId);
        if (phase != RoomPhase.PLAYING) {
            stop(roomId);
            return;
        }
        
        boolean isPve = gomokuService.getMode(roomId) == Mode.PVE;
        char ai = gomokuService.getAiPiece(roomId);
        char sideToMove = state.current();
        boolean aiTurn = isPve && (sideToMove == ai);
        
        // PVE and AI not timed → stop
        if (aiTurn && !aiTimed) { stop(roomId); return; }
        
        // start/resume
        String key = key(roomId);
        String owner = String.valueOf(sideToMove);
        String version = gomokuService.getGameId(roomId);
        long deadline = System.currentTimeMillis() + turnSeconds * 1000L;
        scheduler.startOrResume(key, owner, deadline, version,
                (k, o, v) -> handleTimeout(k, o));
    }
    
    // handle timeout: forfeit and broadcast
    private void handleTimeout(String key, String owner) {
        String roomId = extractRoomId(key);
        char side = owner.charAt(0);
        
        GomokuState before = gomokuService.getState(roomId);
        GomokuState after = gomokuService.resign(roomId, side);  // authoritative forfeit
        
        // save state to Redis (CAS)
        GameStateRecord rec = buildRecord(after, roomId, gameId, expectedStep + 1);
        gameStateRepository.updateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, 0L);
        
        // broadcast TIMEOUT, STATE, SNAPSHOT
        // ...
    }
    
    public void stop(String roomId) {
        scheduler.stop(key(roomId));
    }
    
    private String key(String roomId) { return "gomoku:" + roomId; }
    private String extractRoomId(String key) {
        return key.startsWith("gomoku:") ? key.substring("gomoku:".length()) : key;
    }
    private String topic(String roomId) { return "/topic/room." + roomId; }
}
```

### 1.3 Interface Layer (controller layer)

Package: `com.gamehub.gameservice.games.gomoku.interfaces.ws`

Class: `GomokuWsController`

Responsibilities:
- Handle WebSocket requests (place/restart/resign), broadcast `STATE/SNAPSHOT`
- After each `STATE` broadcast, call `TurnClockCoordinator#syncFromState(roomId, state)` to align turn timer
  - Before restart/resign call `coordinator.stop(roomId)` to end old timer

Key code:
```java
@Controller
@RequiredArgsConstructor
public class GomokuWsController {
    private final TurnClockCoordinator coordinator;
    
    // sync countdown after move
    private void sendState(String roomId, GomokuState state) {
        // ... broadcast STATE and SNAPSHOT
        coordinator.syncFromState(roomId, state);  // align countdown
    }
    
    // stop timer before restart/resign
    @MessageMapping("/gomoku.restart")
    public void restart(/* ... */) {
        coordinator.stop(roomId);  // end old timer
        // ... restart logic
    }
}
```

---

## II. Key Data

### 2.1 Redis Storage Structure

State persistence (by `CountdownSchedulerImpl`):
- Key: `countdown:{key}` (e.g., `countdown:gomoku:room-123`)
- Value: `CountdownState` (JDK serialization)
  ```java
  public static class CountdownState implements Serializable {
      public String key;           // business key (e.g., "gomoku:room-123")
      public String owner;         // side being timed ("X"/"O")
      public String version;       // turn version (gameId, for idempotency/protection)
      public long deadlineEpochMs; // absolute deadline ms
  }
  ```
- TTL: 24h

Holder lock (distributed mutex):
- Key: `countdown:holder:{key}` (e.g., `countdown:holder:gomoku:room-123`)
- Value: node ID (`nodeId`)
- TTL: 10s (hardcoded now; should externalize)
- Implementation: Redis SETNX (`setIfAbsent`)

Key code:
```java
// CountdownSchedulerImpl
private String stateKey(String key) { return "countdown:" + key; }
private String holderKey(String key) { return "countdown:holder:" + key; }

private void saveState(CountdownState st) {
    redis.opsForValue().set(stateKey(st.key), st, Duration.ofSeconds(24 * 60 * 60));
}

private boolean tryAcquireHolder(String key) {
    String lockKey = holderKey(key);
    Boolean ok = redis.opsForValue().setIfAbsent(lockKey, nodeId, Duration.ofSeconds(10));
    return Boolean.TRUE.equals(ok);
}
```

---

## III. Lifecycle and Restore

### 3.1 App Startup Flow

Trigger: `@EventListener(ApplicationReadyEvent.class)`

Key code:
```java
// TurnClockCoordinator.onReady()
@EventListener(ApplicationReadyEvent.class)
public void onReady() {
    // 1) register TICK listener
    scheduler.setTickListener((key, owner, deadlineMs, left) -> {
        String roomId = extractRoomId(key);
        BroadcastEvent tick = new BroadcastEvent();
        tick.setType("TICK");
        tick.setPayload(Map.of("left", (int) left, "side", owner, "deadlineEpochMs", deadlineMs));
        messaging.convertAndSend("/topic/room." + roomId, tick);
    });
    
    // 2) restore unexpired countdowns
    int restored = scheduler.restoreAllActive((key, owner, version) -> handleTimeout(key, owner));
    log.info("Coordinator ready: restored {} countdown tasks", restored);
}
```

Restore logic (`CountdownSchedulerImpl.restoreAllActive()`):
```java
@Override
public int restoreAllActive(TimeoutHandler onTimeout) {
    // scan persisted countdowns
    Set<String> keys = redis.keys(stateKey("*"));  // ⚠️ should use SCAN
    if (keys == null || keys.isEmpty()) return 0;
    int restored = 0;
    int expiredCleaned = 0;
    int expiredHandled = 0;
    for (String redisKey : keys) {
        CountdownState st = loadStateByRedisKey(redisKey);
        if (st == null) continue;
        long remainMs = st.deadlineEpochMs - System.currentTimeMillis();
        if (remainMs <= 0) {
            redis.delete(redisKey);
            expiredCleaned++;
            if (tryAcquireHolder(st.key)) { 
                safeTimeout(onTimeout, st); 
                expiredHandled++; 
            }
            continue;
        }
        fireTick(st);  // immediate TICK
        ScheduledFuture<?> fut = scheduler.scheduleAtFixedRate(
                () -> tickTask(st, onTimeout), 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        activeTasks.put(st.key, fut);
        restored++;
    }
    log.info("Countdown restoreAllActive done: restored={}, expiredCleaned={}, expiredHandled={}",
            restored, expiredCleaned, expiredHandled);
    return restored;
}
```

---

## IV. Thread Model

### 4.1 Thread Pool Config

Config class: `clock.ClockSchedulerConfig`

Key code:
```java
@Configuration
public class ClockSchedulerConfig {
    @Value("${scheduler.clock.corePoolSize:2}")
    private int corePoolSize;
    
    @Bean(name = "turnClockScheduler")
    public ScheduledThreadPoolExecutor turnClockScheduler() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "countdown-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                corePoolSize, tf, new ThreadPoolExecutor.DiscardPolicy());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
```

Thread model:
- Pool: `ScheduledThreadPoolExecutor` (core threads default 2)
- Names: `countdown-1`, `countdown-2`, ...
- Daemon threads: yes
- Frequency: every second (`scheduleAtFixedRate(..., 1, 1, TimeUnit.SECONDS)`)
- Tasks stored: `ConcurrentMap<String, ScheduledFuture<?>> activeTasks` (key -> handle)

---

## V. Main Call Chains

### 5.1 Sync Countdown After Move

Full chain:
```
Frontend move → WS /app/gomoku.place
  ↓
GomokuWsController.place()
  ↓
GomokuService.place() → apply move, CAS save
  ↓
GomokuWsController.sendState(roomId, state)
  ↓
Broadcast STATE and SNAPSHOT
  ↓
TurnClockCoordinator.syncFromState(roomId, state)
  ↓
If end: coordinator.stop(roomId)
If PVE and aiTimed=false and AI turn: coordinator.stop(roomId)
Else: scheduler.startOrResume(key, owner, deadline, version, timeoutHandler)
```

Key code:
```java
// GomokuWsController.sendState()
private void sendState(String roomId, GomokuState state) {
    BroadcastEvent stateEvt = new BroadcastEvent();
    stateEvt.setType("STATE");
    stateEvt.setPayload(new StatePayload(state, series));
    messaging.convertAndSend("/topic/room." + roomId, stateEvt);
    
    // SNAPSHOT ...
    
    coordinator.syncFromState(roomId, state);
}

// TurnClockCoordinator.syncFromState()
public void syncFromState(String roomId, GomokuState state) {
    if (state.over()) { stop(roomId); return; }
    
    RoomPhase phase = gomokuService.getRoomPhase(roomId);
    if (phase != RoomPhase.PLAYING) {
        stop(roomId);
        return;
    }
    
    boolean isPve = gomokuService.getMode(roomId) == Mode.PVE;
    char ai = gomokuService.getAiPiece(roomId);
    char sideToMove = state.current();
    boolean aiTurn = isPve && (sideToMove == ai);
    
    if (aiTurn && !aiTimed) { stop(roomId); return; }
    
    String key = "gomoku:" + roomId;
    String owner = String.valueOf(sideToMove);
    String version = gomokuService.getGameId(roomId);
    long deadline = System.currentTimeMillis() + turnSeconds * 1000L;
    scheduler.startOrResume(key, owner, deadline, version,
            (k, o, v) -> handleTimeout(k, o));
}
```

### 5.2 Per-Second TICK Flow

Chain:
```
ScheduledThreadPoolExecutor every second
  ↓
CountdownSchedulerImpl.tickTask(state, onTimeout)
  ↓
Load latest state from Redis
  ↓
Compute remaining
  ↓
If expired:
  - tryAcquireHolder(key)
  - stop(key)
  - safeTimeout(onTimeout, state)
Else:
  - fireTick(state)
  ↓
TurnClockCoordinator TickListener
  ↓
Assemble TICK and broadcast /topic/room.{roomId}
```

Key code:
```java
// CountdownSchedulerImpl.tickTask()
private void tickTask(CountdownState state, TimeoutHandler onTimeout) {
    CountdownState latest = loadState(state.key);  // Redis as source
    if (latest == null) {
        stop(state.key);
        return;
    }
    
    long remainMs = latest.deadlineEpochMs - System.currentTimeMillis();
    if (remainMs <= 0) {
        if (tryAcquireHolder(state.key)) {
            stop(state.key);
            safeTimeout(onTimeout, latest);
        }
        return;
    }
    
    fireTick(latest);
}

// TickListener registered in onReady()
scheduler.setTickListener((key, owner, deadlineMs, left) -> {
    String roomId = extractRoomId(key);
    BroadcastEvent tick = new BroadcastEvent();
    tick.setType("TICK");
    tick.setPayload(Map.of("left", (int) left, "side", owner, "deadlineEpochMs", deadlineMs));
    messaging.convertAndSend("/topic/room." + roomId, tick);
});
```

### 5.3 Timeout Flow

Chain:
```
Countdown expires
  ↓
CountdownSchedulerImpl.tickTask() sees remainMs <= 0
  ↓
tryAcquireHolder(key) SETNX
  ↓
safeTimeout(onTimeout, latest)
  ↓
TurnClockCoordinator.handleTimeout(key, owner)
  ↓
gomokuService.resign(roomId, side) authoritative forfeit
  ↓
gameStateRepository.updateAtomically(...) CAS save
  ↓
Broadcast TIMEOUT, STATE, SNAPSHOT
```

Key code:
```java
// TurnClockCoordinator.handleTimeout()
private void handleTimeout(String key, String owner) {
    log.info("Turn timeout handling");
    String roomId = extractRoomId(key);
    char side = (owner == null || owner.isEmpty()) ? 0 : owner.charAt(0);
    
    GomokuState before = gomokuService.getState(roomId);
    final String gameId = gomokuService.getGameId(roomId);
    int expectedStep = computeExpectedStep(before.board());
    char expectedTurn = before.current();
    
    GomokuState after = gomokuService.resign(roomId, side);
    
    GameStateRecord rec = buildRecord(after, roomId, gameId, expectedStep + 1);
    try {
        gameStateRepository.updateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, 0L);
    } catch (Exception e) {
        log.warn("Save timeout state to Redis failed: {}", e.getMessage());
    }
    
    BroadcastEvent timeout = new BroadcastEvent();
    timeout.setRoomId(roomId);
    timeout.setType("TIMEOUT");
    timeout.setPayload(java.util.Map.of("side", owner));
    messaging.convertAndSend(topic(roomId), timeout);
    
    var sv = gomokuService.getSeries(roomId);
    BroadcastEvent stateEvt = new BroadcastEvent();
    stateEvt.setRoomId(roomId);
    stateEvt.setType("STATE");
    stateEvt.setPayload(new StatePayload(after, sv));
    messaging.convertAndSend(topic(roomId), stateEvt);
    
    Object snap = gomokuService.snapshot(roomId);
    BroadcastEvent snapEvt = new BroadcastEvent();
    snapEvt.setRoomId(roomId);
    snapEvt.setType("SNAPSHOT");
    snapEvt.setPayload(snap);
    messaging.convertAndSend(topic(roomId), snapEvt);
}
```

---

## VI. Component List

### 6.1 Config Layer

`clock.ClockSchedulerConfig`:
- Provides `turnClockScheduler` pool Bean
- Core threads (`scheduler.clock.corePoolSize`, default 2)
- Thread names `countdown-N`
- Daemon threads: yes

`clock.ClockAutoConfig`:
- Provides `CountdownScheduler` Bean
- Inject Redis and pool into `CountdownSchedulerImpl`

Key code:
```java
// ClockAutoConfig
@Bean
public CountdownScheduler countdownScheduler(
        RedisTemplate<String, Object> redisTemplate,
        @Qualifier("turnClockScheduler") ScheduledThreadPoolExecutor turnClockScheduler) {
    return new CountdownSchedulerImpl(redisTemplate, turnClockScheduler);
}
```

### 6.2 Engine Layer

`clock.scheduler.CountdownScheduler`: interface  
`clock.scheduler.CountdownSchedulerImpl`: implementation

### 6.3 Coordination Layer

`games.gomoku.application.TurnClockCoordinator`: application orchestrator bridging generic engine and Gomoku rules

### 6.4 Interface Layer

`games.gomoku.interfaces.ws.GomokuWsController`: WebSocket controller calling coordinator

---

## VII. Constraints and Boundaries

### 7.1 Engine Constraints

- Engine only drives by absolute `deadlineEpochMs`; business meaning of tick/timeout is up to coordinator
- `version` is just pass-through to `onTimeout` for idempotency/protection; engine does not parse
- Redis keyspace naming adjustable; currently `countdown:` prefix

### 7.2 Coordinator Constraints

- Does not manage thread pool or Redis; engine does
- Does not handle move inputs; WebSocket controller does
- Sole responsibility: map “when to time, how to notify, how to handle expiry” business rules into engine callbacks/calls

---

## VIII. Known Issues and Improvement Plan

### 8.1 Restore Scan Uses KEYS

Problem: `CountdownSchedulerImpl.restoreAllActive()` line 134 uses `redis.keys(stateKey("*"))`, blocking risk.

Impact: large keyspace, KEYS can stall Redis.

Plan: switch to SCAN cursor.

Current:
```java
Set<String> keys = redis.keys(stateKey("*"));  // ⚠️ blocking risk
```

Planned:
```java
// use SCAN cursor
String cursor = "0";
int restored = 0;
do {
    ScanOptions options = ScanOptions.scanOptions()
            .match(stateKey("*"))
            .count(100)
            .build();
    Cursor<String> scan = redis.scan(options);
    // ... process scan
} while (!cursor.equals("0"));
```

### 8.2 Holder Lock TTL Hardcoded

Problem: `CountdownSchedulerImpl.tryAcquireHolder()` line 236 hardcodes 10s.

Impact: cannot tune per business.

Plan: externalize config (e.g., `countdown.holder.ttl.seconds`).

Current:
```java
Boolean ok = redis.opsForValue().setIfAbsent(lockKey, nodeId, Duration.ofSeconds(10));  // ⚠️ hardcoded
```

### 8.3 Serialization Method

Problem: uses JDK serialization (RedisTemplate default).

Impact: poor cross-version/cross-language.

Plan: switch to JSON serialization.

---

## IX. FAQ

### Q1: Why both engine and coordinator send `TICK`?

A: Only coordinator sends (engine only callbacks); coordinator converts callback to WebSocket broadcast.

Code:
```java
// engine only callback
private void fireTick(CountdownState state) {
    TickListener l = tickListener;
    if (l == null) return;
    long left = Math.max(0, (state.deadlineEpochMs - System.currentTimeMillis()) / 1000);
    l.onTick(state.key, state.owner, state.deadlineEpochMs, left);  // callback only
}

// coordinator broadcasts
scheduler.setTickListener((key, owner, deadlineMs, left) -> {
    // ... build TICK event
    messaging.convertAndSend("/topic/room." + roomId, tick);  // coordinator broadcast
});
```

### Q2: Will restart cause duplicate forfeit?

A: Expiry guarded by holder lock to run once; TICK may come from multiple nodes, not affecting authority.

Code:
```java
// holder lock ensures single timeout execution
if (remainMs <= 0) {
    if (tryAcquireHolder(state.key)) {  // SETNX
        stop(state.key);
        safeTimeout(onTimeout, latest);
    }
    return;
}
```

### Q3: Why does `stop()` clean Redis state?

A: Prevent mistaken restore after restart. If not cleaned, `restoreAllActive()` would restore stopped countdowns.

Code:
```java
@Override
public void stop(String key) {
    activeTasks.remove(key);
    // clean Redis state and holder to avoid restore
    redis.delete(stateKey(key));
    redis.delete(holderKey(key));
}
```

---

## X. Full Code File List

### 10.1 Generic Scheduling Engine

- Interface: `clock/scheduler/CountdownScheduler.java`
- Implementation: `clock/scheduler/CountdownSchedulerImpl.java`
- Config: `clock/ClockSchedulerConfig.java` (thread pool)
- Auto-config: `clock/ClockAutoConfig.java` (Bean wiring)

### 10.2 Business Coordination Layer

- Coordinator: `games/gomoku/application/TurnClockCoordinator.java`

### 10.3 Interface Layer

- Controller: `games/gomoku/interfaces/ws/GomokuWsController.java`

---

## XI. Config Items

### 11.1 Thread Pool Config

```yaml
scheduler:
  clock:
    corePoolSize: 2  # countdown scheduler core threads (default 2)
```

### 11.2 Gomoku Countdown Config

```yaml
gomoku:
  turn:
    seconds: 30      # per-turn duration seconds (default 30)
    aiTimed: false   # whether AI is timed in PVE (default false)
```

### 11.3 Instance ID Config

```yaml
instance:
  id: ${spring.application.name}-${random.value}  # node ID (for holder lock)
```

---

## XII. Redis Keyspace Design

### 12.1 Countdown State

- Pattern: `countdown:{key}`
- Example: `countdown:gomoku:room-123`
- Value type: `CountdownState` (JDK serialization)
- TTL: 24h

### 12.2 Holder Lock

- Pattern: `countdown:holder:{key}`
- Example: `countdown:holder:gomoku:room-123`
- Value type: String (node ID)
- TTL: 10s (hardcoded)

---

## XIII. WebSocket Events

### 13.1 TICK Event

Topic: `/topic/room.{roomId}`

Format:
```json
{
  "roomId": "room-123",
  "type": "TICK",
  "payload": {
    "left": 25,
    "side": "X",
    "deadlineEpochMs": 1703123456789
  }
}
```

### 13.2 TIMEOUT Event

Topic: `/topic/room.{roomId}`

Format:
```json
{
  "roomId": "room-123",
  "type": "TIMEOUT",
  "payload": {
    "side": "X"
  }
}
```

---

## XIV. Design Principles Summary

Principles I followed:

1. **Decouple engine and business**: engine only schedules/persists
2. **Coordinator owns business orchestration**: when to time, how to notify, how to handle expiry
3. **Distributed safety**: holder lock ensures timeout runs once
4. **Restart restore**: Redis persistence restores unexpired countdowns
5. **Immediate feedback**: send first TICK immediately on start for better UX

