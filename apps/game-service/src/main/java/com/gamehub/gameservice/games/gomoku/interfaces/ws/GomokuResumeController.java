package com.gamehub.gameservice.games.gomoku.interfaces.ws;

import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.ResumeMessages;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.ResumeMessages.FullSync;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.ResumeMessages.ResumeCmd;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import lombok.RequiredArgsConstructor;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.time.Clock;

/**
 * 五子棋 WebSocket 恢复控制器：
 * 客户端刷新 / 重连时，通过该端点重新绑定座位并返回当前房间完整快照。
 */
@Controller
@RequiredArgsConstructor
public class GomokuResumeController {

    /** 五子棋领域服务（负责房间/棋局状态） */
    private final GomokuService gomoku;
    /** 用于生成服务器时间戳，方便前端校时 */
    private final Clock clock = Clock.systemUTC();

    /**
     * 客户端发送 ResumeCmd 到 /app/gomoku.resume 时触发。
     * 根据 seatKey 恢复玩家座位，并把房间快照以点对点方式推送到 /user/queue/gomoku.full。
     */
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
                .seatXUserId(s.seatXUserId)
                .seatOUserId(s.seatOUserId)
                .mode(s.mode)
                .aiSide(s.aiSide)
                .rule(s.rule)
                .phase(s.phase)
                .createdAt(s.createdAt)
                .seriesView(new FullSync.SeriesView(s.round, s.scoreX, s.scoreO))
                .board(new FullSync.BoardView(s.boardSize, cellsCopy))
                .sideToMove(s.sideToMove)
                .turnSeq(s.turnSeq)
                .deadlineEpochMs(s.deadlineEpochMs)
                .serverEpochMsWhenSent(clock.millis())
                .outcome(s.outcome)
                .readyStatus(s.readyStatus)
                .build();
    }

    /**
     * 深拷贝棋盘二维数组，避免直接暴露可变数组给前端。
     */
    private static char[][] deepCopy(char[][] src) {
        if (src == null) return null;
        char[][] dst = new char[src.length][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i] != null ? src[i].clone() : null;
        }
        return dst;
    }
}
