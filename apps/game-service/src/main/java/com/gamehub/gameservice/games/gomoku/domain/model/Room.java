package com.gamehub.gameservice.games.gomoku.domain.model;

import com.gamehub.gameservice.games.gomoku.domain.ai.GomokuAI;
import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import lombok.Data;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  游戏房间实体
 */
@Data
public class Room {

    // ---- 基本信息 ----
    private final String id;
    private final Mode mode;
    private final Rule rule;
    private final char aiPiece;    // 'X' 或 'O'
    private final GomokuAI ai;

    // ---- 对局串（房间内多盘）----
    private final Series series = new Series();

    // ---- 会话 ↔ 执子 绑定（保持原三者语义）----
    private final Map<String, Character> seatBySession = new ConcurrentHashMap<>(); // sessionId -> 执子
    // --- 刷新恢复所需的最小信息（房间作用域内）
    /** 坐席key seatKey -> 'X'/'O' */
    private final Map<String, Character> seatKeyToSeat = new ConcurrentHashMap<>();
    /** 'X'/'O' -> 当前会话ID（用于“后连踢前”等策略；你可按需使用） */
    private final Map<Character, String> seatToSessionId = new ConcurrentHashMap<>();

    private volatile String seatXSessionId; // 黑方（X）
    private volatile String seatOSessionId; // 白方（O）

    public Room(String id, Mode mode, Rule rule, char aiPiece, GomokuAI ai,String gameId) {
        this.id = id;
        this.mode = mode;
        this.rule = rule;
        this.aiPiece = Character.toUpperCase(aiPiece);
        this.ai = ai;
        // 构造即开第一盘（与原逻辑一致），并推进局号自增指针
        int idx = this.series.getNextIndex();
        this.series.setCurrent(new Game(idx, gameId));
        this.series.setNextIndex(idx + 1);
    }

    public Character getSideBySession(String sessionId) {
        return seatBySession.get(sessionId);
    }

    public boolean occupied(char side) {
        return (side == Board.BLACK) ? (seatXSessionId != null) : (seatOSessionId != null);
    }

    public void bind(String sessionId, char side) {
        seatBySession.put(sessionId, side);
        if (side == Board.BLACK) seatXSessionId = sessionId; else seatOSessionId = sessionId;
    }


    // ---- 房间内对局信息 ----
    @Data
    public class Series {
        // 当前盘
        private Game current;
        // 黑方胜局数
        private int blackWins;
        // 白方胜局数
        private int whiteWins;
        // 平局局数
        private int draws;
        // 自增局号
        int nextIndex = 1;

        public void incBlackWins() { this.blackWins++; }
        public void incWhiteWins() { this.whiteWins++; }
        public void incDraws()     { this.draws++; }
    }
}
