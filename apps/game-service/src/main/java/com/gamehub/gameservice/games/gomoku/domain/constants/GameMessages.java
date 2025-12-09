package com.gamehub.gameservice.games.gomoku.domain.constants;

/**
 * 五子棋游戏相关的消息常量
 * 统一管理所有用户可见的提示消息，避免硬编码
 * 
 * 使用示例：
 *   String reason = GameMessages.KICKED_OUT_REASON;
 *   sendMessage(userId, GameMessages.KICKED_OUT_TITLE);
 */
public final class GameMessages {
    
    private GameMessages() {
        // 工具类，禁止实例化
    }
    
    // ========== 踢人相关消息 ==========
    
    /** 被踢出房间的标题 */
    public static final String KICKED_OUT_TITLE = "你已被踢出房间";
    
    /** 被踢出房间的原因说明 */
    public static final String KICKED_OUT_REASON = "可返回大厅加入其他房间或创建新房间";
    
    /** 踢人成功的提示（需要格式化，传入被踢玩家名称） */
    public static final String KICK_PLAYER_SUCCESS = "已将 %s 移出房间";
    
    /**
     * 格式化踢人成功消息
     * @param playerName 被踢玩家名称
     * @return 格式化后的消息
     */
    public static String formatKickSuccess(String playerName) {
        return String.format(KICK_PLAYER_SUCCESS, playerName);
    }
    
    // ========== 游戏状态消息 ==========
    
    /** 游戏开始 */
    public static final String GAME_STARTED = "游戏开始";
    
    /** 游戏结束 */
    public static final String GAME_OVER = "游戏结束";
    
    /** 你的回合 */
    public static final String YOUR_TURN = "你的回合";
    
    /** 对手回合 */
    public static final String OPPONENT_TURN = "对手回合";
    
    /** 游戏尚未开始 */
    public static final String GAME_NOT_STARTED = "游戏尚未开始，请先双方准备并由房主开始游戏";
    
    /** 未轮到该方走棋 */
    public static final String NOT_YOUR_TURN = "未轮到该方走棋（当前应为 %s）";
    
    /**
     * 格式化未轮到该方走棋消息
     */
    public static String formatNotYourTurn(String currentSide) {
        return String.format(NOT_YOUR_TURN, currentSide);
    }
    
    // ========== 错误消息 ==========
    
    /** 踢人失败 */
    public static final String KICK_FAILED = "踢人失败";
    
    /** 禁手 */
    public static final String FORBIDDEN_MOVE = "禁手";
    
    /** 黑方禁手详细说明 */
    public static final String FORBIDDEN_MOVE_DETAIL = "黑方禁手（长连 / 四四 / 三三）";
    
    /** 非法落子 */
    public static final String ILLEGAL_MOVE = "非法落子";
    
    /** 房间不存在 */
    public static final String ROOM_NOT_FOUND = "房间不存在";
    
    /** 只有房主可以踢人 */
    public static final String ONLY_OWNER_CAN_KICK = "只有房主可以踢人";
    
    /** 不能踢自己 */
    public static final String CANNOT_KICK_SELF = "不能踢自己";
    
    /** 游戏进行中不可踢人 */
    public static final String CANNOT_KICK_IN_GAME = "游戏进行中不可踢人";
    
    /** PVE模式不支持踢人 */
    public static final String PVE_MODE_NO_KICK = "PVE模式不支持踢人";
    
    /** 目标玩家不在房间内 */
    public static final String TARGET_NOT_IN_ROOM = "目标玩家不在房间内";
    
    // ========== 系统消息 ==========
    
    /** 玩家加入房间 */
    public static final String PLAYER_JOINED = "%s 加入房间";
    
    /** 玩家离开房间 */
    public static final String PLAYER_LEFT = "%s 离开房间";
    
    /**
     * 格式化玩家加入消息
     */
    public static String formatPlayerJoined(String playerName) {
        return String.format(PLAYER_JOINED, playerName);
    }
    
    /**
     * 格式化玩家离开消息
     */
    public static String formatPlayerLeft(String playerName) {
        return String.format(PLAYER_LEFT, playerName);
    }
}

