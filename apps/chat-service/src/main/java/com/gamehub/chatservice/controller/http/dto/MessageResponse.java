package com.gamehub.chatservice.controller.http.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 消息列表响应 DTO
 */
@Data
public class MessageResponse {
    private String messageId;
    private String sessionId;
    private String senderId;
    private String messageType;
    private String content;
    private OffsetDateTime createdAt;
    private Boolean isRecalled;
}
