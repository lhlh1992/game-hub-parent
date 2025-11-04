## 倒计时时钟功能说明（总体设计）

### 目标
- 将“倒计时调度”从具体业务中解耦，形成可复用底座。
- 业务（如五子棋）只关心：何时启动/停止、每秒展示、到期如何处理。

### 分层与职责
1) 通用调度引擎（infra）
- 接口：`clock/scheduler/CountdownScheduler`
- 实现：`clock/scheduler/CountdownSchedulerImpl`
- 职责：
  - 管理 key 的倒计时：`startOrResume`、`stop`、`restoreAllActive`
  - 每秒触发 `TickListener#onTick(key, owner, deadlineMs, left)`
  - 到期触发 `TimeoutHandler#onTimeout(key, owner, version)`
  - 将状态持久化在 Redis（`countdown:{key}`），并用 SETNX 实现 holder 锁（`countdown:holder:{key}`）保证分布式下仅一处做超时处理
  - 使用注入的 `ScheduledThreadPoolExecutor` 周期调度
- 不做：任何业务逻辑（广播/判负等）

2) 业务协调层（domain adapter）
- 类：`clock/gomoku/GomokuTurnClockCoordinator`
- 职责：
  - 从 `GomokuState` 计算通用参数并驱动调度：
    - key=`"gomoku:"+roomId`
    - owner=当前轮到的一方（`"X"/"O"`）
    - version=当前盘的 `gameId`
    - deadlineMs=当前时间 + 单回合秒数
  - 监听调度引擎的 `tick` 回调，转成房间内 `TICK` 事件并广播
  - 监听调度引擎的 `timeout` 回调，调用 `gomokuService.resign` 判负，并广播 `TIMEOUT/STATE/SNAPSHOT`
  - 处理 PVE `aiTimed=false` 的不计时规则、终局停止计时

3) 接口层（controller）
- 类：`games/gomoku/interfaces/ws/GomokuWsController`
- 职责：
  - 处理落子/重开/认输等请求，广播 `STATE/SNAPSHOT`
  - 在每次 `STATE` 广播后，调用 `GomokuTurnClockCoordinator#syncFromState(roomId, state)` 以对齐回合计时
  - 在重开/认输前调用 `coordinator.stop(roomId)` 终止旧回合计时

### 关键数据
- 状态持久化结构（由 `CountdownSchedulerImpl` 写入 Redis）
  - `countdown:{key}` → { key, owner, version, deadlineEpochMs }
  - `countdown:holder:{key}` → SETNX 锁，10s 过期（防抖，多节点只一个判定超时）

### 生命周期与恢复
- 应用启动：`GomokuTurnClockCoordinator#onReady`
  - 注册 `TickListener` 用于把引擎 tick 转成房间 `TICK`
  - 调用 `scheduler.restoreAllActive(...)` 恢复所有未过期倒计时；若某些已过期，尝试触发一次 `onTimeout`

### 线程模型
- 线程池：`ScheduledThreadPoolExecutor`（由 `ClockSchedulerConfig` 提供 Bean），引擎内部按秒调度
- 引擎保持 `key -> ScheduledFuture` 表，用于取消任务

### 主要调用链
- 前端落子 → Controller `sendState(...)` → `coordinator.syncFromState(roomId, state)` →
  - 若终局：`coordinator.stop`
  - 若 PVE 且 `aiTimed=false` 且轮到 AI：`coordinator.stop`
  - 否则：`scheduler.startOrResume(key, owner, deadline, version, timeoutHandler)`
- 引擎每秒 tick：回调 `TickListener` → 协调器广播 `TICK`
- 引擎到期：回调 `TimeoutHandler` → 协调器 `gomokuService.resign` + 广播 `TIMEOUT/STATE/SNAPSHOT`

### 组件清单
- 配置：`clock/ClockSchedulerConfig` 提供 `turnClockScheduler` 线程池 Bean
- 装配：`clock/ClockAutoConfig` 提供 `CountdownScheduler` Bean（注入 Redis 与 线程池）
- 引擎：`clock/scheduler/CountdownScheduler`、`CountdownSchedulerImpl`
- 协调：`clock/gomoku/GomokuTurnClockCoordinator`
- 接口：`games/gomoku/interfaces/ws/GomokuWsController` 调用协调器

### 约束与边界
- 引擎仅以绝对时间 `deadlineEpochMs` 驱动；tick/timeout 的业务含义交由协调器定义
- 版本 `version` 仅透传给 `onTimeout`（供业务幂等/保护使用），引擎不解析
- Redis 键空间可按需调整命名

### 常见问题
- Q: 为什么引擎和协调器都要发 `TICK`？
  - A: 只有协调器发（引擎只回调），协调器负责把回调转成 WebSocket 广播。
- Q: 重启后会不会判罚重复？
  - A: 到期由 holder 锁保证只执行一次；tick 广播允许多节点同时发，不影响权威性。


