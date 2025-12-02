package com.gamehub.gameservice.games.gomoku.interfaces.http.dto;

import com.gamehub.gameservice.games.gomoku.domain.dto.RoomMeta;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 在线房间列表的单行摘要信息。
 * 这是 HTTP 层专用 DTO，从 RoomMeta 映射而来。
 */
@Data
@AllArgsConstructor
public class RoomSummary {
    private String roomId;
    private String ownerUserId;
    private String mode;
    private String rule;
    private String phase;
    private long createdAt;
    private boolean deleted;

    public static RoomSummary from(RoomMeta meta) {
        return new RoomSummary(
                meta.getRoomId(),
                meta.getOwnerUserId(),
                meta.getMode(),
                meta.getRule(),
                meta.getPhase(),
                meta.getCreatedAt(),
                false
        );
    }

    public static RoomSummary tombstone(String roomId) {
        return new RoomSummary(roomId, null, null, null, null, 0L, true);
    }
}


