package com.gamehub.gameservice.games.gomoku.domain.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * SeatsBinding
 * -------------------------------------------------------
 * 房间内的座位/会话绑定信息（可持久化）。
 * - 不包含运行态的 WebSocket Session 对象，只保存 sessionId 字符串。
 * -------------------------------------------------------
 * Fields:
 * - seatXSessionId / seatOSessionId: X/O 座位当前绑定的 sessionId。
 * - seatBySession: sessionId -> "X"/"O" 的反向映射，用于断线重连鉴权。
 * - readyByUserId: userId -> ready状态（true=已准备，false=未准备），用于准备阶段管理。
 */
@Data
public class SeatsBinding {
    /** 黑方（X）座位绑定的 sessionId */
    private String seatXSessionId;
    /** 白方（O）座位绑定的 sessionId */
    private String seatOSessionId;

    /** sessionId -> "X"/"O" 的映射（支持多端或历史记录） */
    private Map<String, String> seatBySession = new HashMap<>();
    
    /** userId -> ready状态（true=已准备，false=未准备） */
    private Map<String, Boolean> readyByUserId = new HashMap<>();
}
