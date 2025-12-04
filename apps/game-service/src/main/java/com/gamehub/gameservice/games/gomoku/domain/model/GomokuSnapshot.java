package com.gamehub.gameservice.games.gomoku.domain.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only snapshot of a Gomoku room (used by FullSync).
 */
public final class GomokuSnapshot {

    public final String roomId;
    public final boolean seatXOccupied;
    public final boolean seatOOccupied;
    public final String seatXUserId;
    public final String seatOUserId;
    public final long createdAt;
    public final String mode;
    public final Character aiSide;
    public final String rule;
    public final String phase;
    public final int boardSize;
    public final char[][] cells;
    public final Character sideToMove;
    public final long turnSeq;
    public final Long deadlineEpochMs;
    public final int round;
    public final int scoreX;
    public final int scoreO;
    public final String outcome;
    public final Map<String, Boolean> readyStatus;

    public GomokuSnapshot(String roomId,
                          boolean seatXOccupied,
                          boolean seatOOccupied,
                          String seatXUserId,
                          String seatOUserId,
                          long createdAt,
                          String mode,
                          Character aiSide,
                          String rule,
                          String phase,
                          int boardSize,
                          char[][] cells,
                          Character sideToMove,
                          long turnSeq,
                          Long deadlineEpochMs,
                          int round,
                          int scoreX,
                          int scoreO,
                          String outcome,
                          Map<String, Boolean> readyStatus) {
        this.roomId = roomId;
        this.seatXOccupied = seatXOccupied;
        this.seatOOccupied = seatOOccupied;
        this.seatXUserId = seatXUserId;
        this.seatOUserId = seatOUserId;
        this.createdAt = createdAt;
        this.mode = mode;
        this.aiSide = aiSide;
        this.rule = rule;
        this.phase = phase;
        this.boardSize = boardSize;
        this.cells = cells;
        this.sideToMove = sideToMove;
        this.turnSeq = turnSeq;
        this.deadlineEpochMs = deadlineEpochMs;
        this.round = round;
        this.scoreX = scoreX;
        this.scoreO = scoreO;
        this.outcome = outcome;
        this.readyStatus = readyStatus == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(readyStatus));
    }
}
