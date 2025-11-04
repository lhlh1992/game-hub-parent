package com.gamehub.gameservice.games.gomoku.interfaces.ws.dto;

import lombok.*;

/**
 * V5.2 刷新后重回棋局 —— 恢复握手与全量同步 DTO
 * 协议约定：
 *  - 客户端在刷新/重连后，向 /app/gomoku.resume 发送 ResumeCmd（带 roomId，seatKey 可空）。
 *  - 服务端点对点（/user/queue/gomoku.full）回 FullSync；之后继续靠原来的广播事件（STATE/TICK/TIMEOUT）。
 *
 * 迁移友好：
 *  - seatKey 现在是“房间内的座位令牌”，未来可被用户登录态的 roomTicket 替换；本 DTO 不变。
 */
public interface ResumeMessages {

    @Data @NoArgsConstructor @AllArgsConstructor
    class ResumeCmd {
        /** 房间ID（必填） */
        private String roomId;
        /**
         * 房间席位令牌（可空）：
         *   - 无账号阶段：识别“本页是否为该房间的持座玩家（X/O）”
         *   - 为空则以观战者身份恢复
         */
        private String seatKey;
    }

    @Data @Builder
    class FullSync {
        private String roomId;

        private Seats seats;                 // X/O 是否被占 + 观战人数（无法统计可置 0）
        private String myRole;               // PLAYER | VIEWER
        private Character mySide;            // 'X' | 'O' | null

        private String mode;                 // PVP | PVE
        private Character aiSide;            // PVE 时 AI 执子；PVP 为 null
        private String rule;                 // STANDARD | RENJU （若当前接口拿不到可置 null）

        private SeriesView seriesView;       // 局数 / 比分
        private BoardView board;             // 棋盘

        private Character sideToMove;        // 当前执子；已结束可为 null
        private long      turnSeq;           // 回合序号（与倒计时对应）
        private Long      deadlineEpochMs;   // 截止时间（非计时可为 null）
        private long      serverEpochMsWhenSent; // 服务器当前时间（校时用）
        private String    outcome;           // 终局：X_WIN / O_WIN / DRAW / null

        @Data @AllArgsConstructor
        public static class Seats { public boolean X; public boolean O; public int viewerCount; }

        @Data @AllArgsConstructor
        public static class SeriesView { public int round; public int scoreX; public int scoreO; }

        @Data @AllArgsConstructor
        public static class BoardView { public int size; public char[][] cells; }
    }
}
