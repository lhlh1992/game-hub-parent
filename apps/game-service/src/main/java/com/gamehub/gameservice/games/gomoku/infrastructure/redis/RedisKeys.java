package com.gamehub.gameservice.games.gomoku.infrastructure.redis;

/**
 * 统一集中管理 Redis Key 的前缀与拼接，避免字符串散落。
 * 未来切换产品/游戏前缀、加租户维度，只改这里。
 */
public final class RedisKeys {

    private static final String PFX = "gomoku:"; // 也可做成可配置常量

    private RedisKeys() {}

    // ---- 房间元信息 ----
    public static String room(String roomId) {
        return PFX + "room:" + roomId;
    }

    // ---- 房间座位/会话绑定 ----
    public static String roomSeats(String roomId) {
        return PFX + "room:" + roomId + ":seats";
    }

    public static String roomSeatKey(String roomId, String seatKey) {
        return PFX + "room:" + roomId + ":seatKey:" + seatKey;
    }

    public static String roomSeatKeyPrefix(String roomId) {
        return PFX + "room:" + roomId + ":seatKey:";
    }

    /** 房间座位占用锁：X / O，用于并发占座互斥 */
    public static String roomSeatLock(String roomId, char seat) {
        return PFX + "room:" + roomId + ":seatLock:" + Character.toUpperCase(seat);
    }

    // ---- 单盘对局状态 ----
    public static String gameState(String roomId, String gameId) {
        return PFX + "room:" + roomId + ":game:" + gameId + ":state";
    }

    // ---- 回合计时锚点 ----
    public static String turnAnchor(String roomId) {
        return PFX + "room:" + roomId + ":turn";
    }

    // ---- AI 意图（可选） ----
    public static String aiPending(String roomId) {
        return PFX + "room:" + roomId + ":ai:pending";
    }

    // 哪个节点持有该房间的计时器（多实例只允许一个节点跑tick/判负）
    public static String turnHolder(String roomId) {
        return PFX + "room:" + roomId + ":turn:holder";
    }

    public static String gameStatePrefix(String roomId) {
        return PFX + "room:" + roomId + ":game:";
    }
    public static String gameStateSuffix() {
        return ":state";
    }

    public static String roomSeries(String roomId) {
        return "gomoku:room:" + roomId + ":series";
    }

    /** 房间内玩家资料缓存（Hash：userId -> UserProfileView 序列化） */
    public static String roomUserProfiles(String roomId) {
        return PFX + "room:" + roomId + ":users";
    }

    /** 在线房间索引（ZSET），score 使用 createdAt（epoch millis） */
    public static String roomIndexKey() {
        return PFX + "rooms:index";
    }

    // ---- 用户维度：正在进行中的房间 ----
    public static String userOngoing(String userId) {
        return PFX + "user:" + userId + ":ongoing";
    }



}
