package com.gamehub.gameservice.games.gomoku.interfaces.http.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 在线房间列表接口的响应体（data 部分）。
 */
@Data
@AllArgsConstructor
public class RoomListResponse {
    private List<RoomSummary> items;
    private Long nextCursor;
    private boolean hasMore;
}


