package com.gamehub.gameservice.games.gomoku.interfaces.ws.dto;

import lombok.Data;

/**
 * WebSocket 消息对象定义（DTO）
 * ----------------------------------------
 * 本类定义了前端与后端通过 WebSocket 交互时使用的消息格式。
 * 包含两种方向：
 *   1. 前端 -> 后端：客户端发起的指令（PlaceCmd）
 *   2. 后端 -> 前端：服务器广播的事件（BroadcastEvent）
 *
 * 这些消息会被 STOMP 封装后，通过 /app/... 和 /topic/... 进行通信。
 */
public class GomokuMessages {


    /**
     * 落子命令（客户端 → 服务端）
     * ---------------------------------------------
     * 客户端点击棋盘后发送的主要指令。
     * 字段：
     *   - roomId ：房间编号；
     *   - gameId ：当前棋局编号；
     *   - x,y    ：棋盘坐标；
     *   - seatKey：座位令牌，可空（用于刷新重入绑定）；
     *   - side   ：执子方（'X' 或 'O'），最终以服务器校验为准。
     */
    @Data
    public static class PlaceCmd {
        private String roomId;
        private String gameId;
        private int x;
        private int y;
        private String seatKey;   //可空；刷新后轻绑定
        private char side; // 棋子方（'X'或'O'）—— 实际逻辑中以服务器校验为准
    }

    /**
     * 广播事件（服务端 → 客户端）
     * ---------------------------------------------
     * 服务端通过 STOMP /topic 推送的统一事件结构。
     * 字段：
     *   - roomId ：所属房间；
     *   - gameId ：当前棋局；
     *   - type   ：事件类型（如 "STATE"、"ERROR"、"TIMEOUT"、"TICK" 等）；
     *   - payload：事件内容（棋盘状态或错误信息）。
     */
    @Data
    public static class BroadcastEvent {
        private String roomId;
        private String gameId;
        private String type;        // "STATE" / "ERROR"
        private Object payload;     // 这里会放 GomokuState 或 错误信息
    }

    /**
     * 简单命令（客户端 → 服务端）
     * ---------------------------------------------
     * 用于无坐标的简单操作，例如认输、重开等。
     * 字段：
     *   - roomId ：房间编号；
     *   - seatKey：座位令牌，可空（刷新重入绑定用）。
     */
    @lombok.Data
    public static class SimpleCmd {
        private String roomId;
        private String seatKey;   //可空；刷新后轻绑定
        // 可按需扩展字段，比如想带 side/备注等再加
    }

    /**
     * 踢人命令（客户端 → 服务端）
     * ---------------------------------------------
     * 房主踢出指定玩家。
     * 字段：
     *   - roomId ：房间编号；
     *   - targetUserId：被踢玩家用户ID；
     *   - seatKey：座位令牌，可空（刷新重入绑定用）。
     */
    @Data
    public static class KickCmd {
        private String roomId;
        private String targetUserId;
        private String seatKey;   //可空；刷新后轻绑定
    }
}
