# 玩家退出房间（Gomoku）处理说明

> 适用范围：`game-service` 模块中所有与五子棋房间/对局相关的退出逻辑。

## 目标

当玩家手动点击“离开房间”时，服务端需要根据房间模式（PVE / PVP）做不同处理，确保：

- 游戏状态不会残留；
- “继续对局”入口立即消失；
- PVP 场景下，其余玩家可以继续或成为新的房主。

## 接口

| 接口 | 方法 | 说明 |
| ---- | ---- | ---- |
| `/api/gomoku/rooms/{roomId}/leave` | `POST` | 玩家主动离开房间，按模式清理或交接 |
| `/api/ongoing-game` | `GET` | 查询是否有进行中的对局（已有实现） |
| `/api/ongoing-game/end` | `POST` | 清理“继续对局”入口（离开房间后同步调用） |

> 本文重点描述 `leave` 接口应执行的逻辑，另外两个接口已在现有实现中使用。

## 流程概览

```
入口 → 校验 JWT & roomId
      ↓
   读取 RoomMeta
      ↓
  判断 Mode
  ├─ PVE → destroyRoom(roomId) + clear ongoing → 返回
  └─ PVP → 进入 PVP 处理流程（见下）
```

### PVP 处理流程

1. **读取房间状态**
   - `SeatsBinding seats = roomRepo.getSeats(roomId)`
   - 内存 `Room r = room(roomId)`（沿用 `GomokuServiceImpl.room()`）

2. **判断离开者**
   - 确认用户是否占据 X 或 O 座位；若本就不在房间，可直接返回成功。

3. **剩余玩家数**
   - 若另一侧空（即房间只剩离开者）→ `destroyRoom(roomId)`，并清理 ongoing。
   - 若仍有对手 → 执行“房主交接 + 解绑”。

4. **房主交接**
   - 如果 `roomMeta.ownerUserId` 等于当前用户，新的 owner 设为另一位玩家的 userId（从 `SeatsBinding` 或 `Room` 的 `seatBySession` Map 获取），并写回 `RoomMeta`。

5. **解绑离开的玩家**
   - 清空 `seatXSessionId`/`seatOSessionId` 中对应的 userId；
   - 从 `seatBySession` Map 中移除；
   - `roomRepo.saveSeats(roomId, seats, ttl)` 写回；
   - 可选：删除该用户的 `seatKey`（若要彻底清理，可为 `seatKey` 建反向索引）。

6. **同步内存与通知**
   - 更新内存 `Room` 对象里的座位状态和 owner。
   - 可向房间广播一个 `PLAYER_LEFT` 或重新发送 `SNAPSHOT`，提示剩余玩家刷新界面。

7. **进行中状态**
   - 无论 PVE/PVP，都调用 `OngoingGameTracker.clear(userId)`，使前端“继续游戏”入口立即消失。

8. **返回体建议**
   - `clearedRoom: boolean`：房间是否被销毁；
   - `newOwner?: string`：若交接房主，返回新的 owner；
   - `playerSide?: 'X' | 'O'`：被移除的座位，可供前端提示。

## destroyRoom(roomId) 建议实现

- `roomRepo.deleteRoom(roomId)`
- `roomRepo.deleteSeats(roomId)`
- 删除所有 `gameState` / `turnRepo` / `seatKey` 等 Redis 数据
- `rooms.remove(roomId)`（内存 Map）
- `ongoingGameTracker.clear(ownerUserId)`（可选：房主退出时已清理）
- 可向订阅者广播“房间已关闭”

## 前端协作点

- 局内 “Leave Room” 按钮：成功调用 `leave` 后，再调用 `/api/ongoing-game/end`，最后跳转 `/lobby`。
- 顶部导航“退出”共用同一套接口，保持体验一致。
- 根据接口返回的 `clearedRoom` 等字段，适当提示用户（例如 “房间已关闭/你已退出房间”）。

## 其它注意事项

- **幂等性**：多端同时退出时，接口应允许重复调用；若用户不再占座，直接返回成功。
- **seatKey 清理**：若 seatKey 需要彻底移除，可在 `issueSeatKey` 时额外记录 userId → seatKey 的映射，退出时据此删除。
- **观战/扩展**：未来支持观战者时，可在“解绑”步骤加入观战者集合的处理，但整体框架不变。

以上内容可作为实现该功能的参考文档，确保前后端对“离开房间”行为有统一理解。***

