package com.gamehub.gameservice.games.gomoku.domain.dto;

import lombok.Data;

/**
 * 回合计时的“锚点”信息（非运行态）
 * - deadlineEpochMs：截止绝对时间（毫秒）
 * - side：当前计时方 'X'/'O'
 * - turnSeq：回合序列号（乐观并发用）
 */
@Data
public class TurnAnchor {
    /** 房间ID（冗余保存） */
    private String roomId;

    /** 棋局ID（冗余保存） */
    private String gameId;
    /** 当前计时方："X"/"O" */
    private String side;
    /** 截止绝对时间（毫秒时间戳） */
    private long deadlineEpochMs;
    /** 回合序列号（每次换手/重开自增） */
    private long turnSeq;
}
