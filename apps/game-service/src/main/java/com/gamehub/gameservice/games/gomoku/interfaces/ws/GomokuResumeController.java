package com.gamehub.gameservice.games.gomoku.interfaces.ws;



import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.time.Clock;

@Controller
@RequiredArgsConstructor                 // ✅ 构造器注入：自动为 final 字段生成构造器
public class GomokuResumeController {

    private final GomokuService gomoku;  // ✅ 由 Spring 通过构造器注入，不再报未初始化
    private final Clock clock = Clock.systemUTC(); // ✅ 直接使用系统 UTC，无需再注入 Bean

    @MessageMapping("/gomoku.resume")
    @SendToUser("/queue/gomoku.full")
    public FullSync onResume(ResumeCmd cmd,
                             @Header("simpSessionId") String sessionId,
                             org.springframework.messaging.simp.SimpMessageHeaderAccessor sha) {
        final String roomId = cmd.getRoomId();
        final String userId = sha.getUser() == null ? null : sha.getUser().getName();

        // 1) seatKey（可空）：尝试把“当前会话”绑定回原座位
        Character mySide = null;
        String myRole = "VIEWER";
        if (cmd.getSeatKey() != null && !cmd.getSeatKey().isBlank()) {
            Character bound = gomoku.bindBySeatKey(roomId, cmd.getSeatKey(), userId);
            if (bound != null) {
                mySide = bound;
                myRole = "PLAYER";
            }
        }

        // 2) 读取房间快照（一次性全量同步）
        GomokuSnapshot s = gomoku.snapshot(roomId);

        // 3) 映射为 FullSync 返回给当前会话（/user/queue）
        char[][] cellsCopy = deepCopy(s.cells);
        return FullSync.builder()
                .roomId(s.roomId)
                .seats(new FullSync.Seats(s.seatXOccupied, s.seatOOccupied, 0))
                .myRole(myRole)
                .mySide(mySide)
                .mode(s.mode)
                .aiSide(s.aiSide)
                .rule(s.rule)
                .phase(s.phase)
                .seriesView(new FullSync.SeriesView(s.round, s.scoreX, s.scoreO))
                .board(new FullSync.BoardView(s.boardSize, cellsCopy))
                .sideToMove(s.sideToMove)            // 若当前项目没有该值，则为 null
                .turnSeq(s.turnSeq)                  // 若无回合数，则为 0
                .deadlineEpochMs(s.deadlineEpochMs)  // 若无倒计时集中存储，则为 null
                .serverEpochMsWhenSent(clock.millis())
                .outcome(s.outcome)                  // "X_WIN"|"O_WIN"|"DRAW"|null
                .readyStatus(s.readyStatus)          // 玩家准备状态
                .build();
    }

    /**
     * 深拷贝棋盘二维数组。
     *
     * 【作用】
     * 复制一份新的棋盘数据，用于返回给前端。
     * 这样做能避免并发修改问题——如果直接把原数组返回，
     * 当服务端继续落子修改棋盘时，前端拿到的引用也会被同步改动。
     * 因此这里为每一行重新 clone 出独立副本。
     *
     * 【逻辑】
     * 1. 判空：如果原数组为空，直接返回 null；
     * 2. 创建同尺寸的新数组；
     * 3. 遍历每一行：
     *      - 若该行非空 → clone 一份；
     *      - 若为空 → 保持 null；
     * 4. 返回全新的二维数组。
     */
    private static char[][] deepCopy(char[][] src) {
        if (src == null) return null;
        char[][] dst = new char[src.length][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i] != null ? src[i].clone() : null;
        }
        return dst;
    }

    // ===== 为最小改动，DTO 先放在本类里；后续可抽到 dto 包 =====

    /**
     * ResumeCmd：前端刷新重连时发送的恢复请求。
     * 含房间ID和座位令牌，用于服务端定位并恢复原棋局。
     */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ResumeCmd {
        private String roomId;   // 房间ID（必填）
        private String seatKey;  // 可空；为空=观战恢复
    }

    /**
     * FullSync：前端刷新或重连时返回的“完整棋局快照”。
     * 含房间信息、棋盘状态、比分、当前执子等，用于一次性同步。
     */
    @Data @Builder
    public static class FullSync {
        private String roomId;

        private Seats seats;                 // X/O 是否被占 + 观战人数（暂 0）
        private String myRole;               // PLAYER | VIEWER
        private Character mySide;            // 'X' | 'O' | null

        private String mode;                 // PVP | PVE
        private Character aiSide;            // PVE 时 AI 执子；PVP 为 null
        private String rule;                 // STANDARD | RENJU
        private String phase;                // WAITING | PLAYING | ENDED

        private SeriesView seriesView;       // 局数 / 比分
        private BoardView board;             // 棋盘

        private Character sideToMove;        // 当前应执子；已结束可为 null
        private long      turnSeq;           // 你的项目目前为 0（后续再补）
        private Long      deadlineEpochMs;   // 暂为 null（后续再补）
        private long      serverEpochMsWhenSent;
        private String    outcome;           // "X_WIN" | "O_WIN" | "DRAW" | null
        private java.util.Map<String, Boolean> readyStatus; // userId -> ready 状态

        @Data @AllArgsConstructor
        public static class Seats { public boolean X; public boolean O; public int viewerCount; }

        @Data @AllArgsConstructor
        public static class SeriesView { public int round; public int scoreX; public int scoreO; }

        @Data @AllArgsConstructor
        public static class BoardView { public int size; public char[][] cells; }
    }
}
