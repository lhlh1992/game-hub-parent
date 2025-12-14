package com.gamehub.chatservice.controller.http.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话列表响应 DTO
 */
@Data
public class SessionResponse {
    private String sessionId;
    private String sessionType;
    private String sessionName;
    private String lastMessage;
    private OffsetDateTime lastMessageTime;
    private long unreadCount;
    private String otherUserId; // 对方用户ID（仅私聊会话使用）
    private String otherUserNickname; // 对方用户昵称（仅私聊会话使用）
    private String otherUserAvatarUrl; // 对方用户头像URL（仅私聊会话使用）
}
