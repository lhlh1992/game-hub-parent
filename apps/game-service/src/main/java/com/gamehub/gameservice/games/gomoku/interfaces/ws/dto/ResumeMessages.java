package com.gamehub.gameservice.games.gomoku.interfaces.ws.dto;

import com.gamehub.gameservice.application.user.UserProfileView;
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

    /**
     * 五子棋房间完整快照（通过 /user/queue/gomoku.full 推送给前端）。
     * 一次性包含房间元信息、座位绑定、棋盘状态、计时与准备状态。
     */
    @Data
    @Builder
    class FullSync {
        /** 房间ID（业务主键） */
        private String roomId;

        /** 座位占用信息（X/O 是否有人，以及观战人数） */
        private Seats seats;                 // X/O 是否被占 + 观战人数（无法统计可置 0）
        /** 当前会话在本房间中的角色：PLAYER / VIEWER */
        private String myRole;
        /** 当前会话执子方：'X' / 'O' / null（观战或未绑定时为 null） */
        private Character mySide;            // 'X' | 'O' | null

        /** 黑棋座位绑定的用户ID（Keycloak userId / sessionId 视实现而定） */
        private String seatXUserId;

        /** 白棋座位绑定的用户ID */
        private String seatOUserId;

        /** 黑棋玩家的详细档案（头像、昵称、战绩等），可能为 null */
        private UserProfileView seatXUserInfo;

        /** 白棋玩家的详细档案，可能为 null */
        private UserProfileView seatOUserInfo;

        /** 对局模式：PVP / PVE */
        private String mode;
        /** PVE 模式下 AI 执子方；PVP 为 null */
        private Character aiSide;            // PVE 时 AI 执子；PVP 为 null
        /** 规则：STANDARD / RENJU */
        private String rule;                 // STANDARD | RENJU （若当前接口拿不到可置 null）

        /** 房间阶段：WAITING / PLAYING / ENDED */
        private String phase;

        /** 房间创建时间（毫秒时间戳），便于前端做排序 / 展示 */
        private long createdAt;

        /** 系列对局信息：当前局数与双方累计比分 */
        private SeriesView seriesView;       // 局数 / 比分
        /** 当前棋盘视图（尺寸 + 网格） */
        private BoardView board;             // 棋盘

        /** 当前应执子：'X' / 'O'；终局时为 null */
        private Character sideToMove;        // 当前执子；已结束可为 null
        /** 回合序号（与倒计时 / 超时判定对应） */
        private long      turnSeq;           // 回合序号（与倒计时对应）
        /** 本回合截止时间（毫秒时间戳），未启用计时则为 null */
        private Long      deadlineEpochMs;   // 截止时间（非计时可为 null）
        /** 服务器发送本快照时的时间戳，前端可用于校时 */
        private long      serverEpochMsWhenSent; // 服务器当前时间（校时用）
        /** 终局结果：X_WIN / O_WIN / DRAW / null（未结束为 null） */
        private String    outcome;           // 终局：X_WIN / O_WIN / DRAW / null

        /** 准备状态：userId -> 是否已准备 */
        private java.util.Map<String, Boolean> readyStatus;

        @Data @AllArgsConstructor
        public static class Seats { public boolean X; public boolean O; public int viewerCount; }

        @Data @AllArgsConstructor
        public static class SeriesView { public int round; public int scoreX; public int scoreO; }

        @Data @AllArgsConstructor
        public static class BoardView { public int size; public char[][] cells; }
    }
}
