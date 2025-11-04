package com.gamehub.gameservice.games.gomoku.domain.dto;

import lombok.Data;

/**
 * AI 意图（用于节点重启后的恢复）
 */
@Data
public class AiIntent {
    /** 房间ID */
    private String roomId;
    /** AI 将要行动的执子："X"/"O" */
    private String side;
    /** 计划执行时间（毫秒） */
    private long scheduledAtMs;
}
