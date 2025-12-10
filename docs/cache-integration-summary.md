## 用户信息缓存与聊天兜底方案（摘要）

### 背景
- 需求：登录后写用户档案缓存；game-service 进入/创建房间时优先读缓存，未命中再 Feign system；前端 chat 在服务端未带 senderName 时兜底显示昵称。
- 目标：降低对数据库/Feign 的依赖，避免缓存失效导致前端显示纯 userId。

### 后端改动
- system-service
  - 用户档案缓存 `user:profile:{userId}` TTL 调整为 2h（变更/封禁/修改资料时刷新或驱逐）。
- game-service
  - 同步使用 2h TTL 的用户档案缓存。
  - 进入/创建房间时，获取房间成员信息：先读缓存，未命中再 Feign system-service，并回写缓存。
- chat-service
  - 使用同一份用户档案缓存，TTL 2h；WS 路径仅读缓存，未命中由前端兜底。

### 前端兜底（chat）
- 房间页维护本地 `userInfoCache`，从房间快照/当前用户信息写入。
- 收到 WS 聊天消息：
  - 优先用 `senderName`；无则用本地缓存；仍无则发起一次 system-service 用户档案请求写入缓存，并回写已收到消息的显示名。
- 聊天滚动优化：仅滚动聊天容器，避免整页上跳。

### 流程要点
1) 登录成功：system-service 写入 Redis 用户档案（2h TTL）。
2) 进入/创建房间：game-service 先查缓存，miss 时 Feign system-service，回写缓存，并将成员信息下发前端。
3) 聊天收消息：若无 senderName，前端尝试本地缓存；仍无则前端调用 system-service 拉档案并更新显示。

### 备注
- 三个服务共用 session Redis（`sessionRedisTemplate`），键前缀 `user:profile:`。
- TTL 统一 2h，降低穿透；资料变更由 system-service 驱逐/刷新。
- chat-service WS 不主动 Feign，保持低延迟；兜底逻辑在前端执行。 


