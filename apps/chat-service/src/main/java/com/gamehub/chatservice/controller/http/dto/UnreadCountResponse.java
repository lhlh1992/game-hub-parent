package com.gamehub.chatservice.controller.http.dto;

import lombok.Data;

/**
 * 未读消息数响应 DTO
 */
@Data
public class UnreadCountResponse {
    private String sessionId;
    private long unreadCount;
}
