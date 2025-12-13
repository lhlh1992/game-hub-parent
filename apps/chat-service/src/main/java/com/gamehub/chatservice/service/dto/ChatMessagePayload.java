package com.gamehub.chatservice.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天消息载体（用于 WS 推送与历史存储）。
 * 支持消息类型：LOBBY（大厅）、ROOM（房间）、PRIVATE（私聊）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessagePayload {
    /**
     * 消息类型：LOBBY（大厅）、ROOM（房间）、PRIVATE（私聊）
     */
    private String type;
    /**
     * 房间 ID（大厅和私聊为 null）
     */
    private String roomId;
    /**
     * 发送者用户 ID（Keycloak 用户ID，String格式）
     */
    private String senderId;
    /**
     * 发送者昵称（从缓存解析，兜底 userId）
     */
    private String senderName;
    /**
     * 接收者用户 ID（仅私聊消息使用，Keycloak 用户ID，String格式）
     */
    private String targetUserId;
    /**
     * 文本内容（已做长度截断/去空白）
     */
    private String content;
    /**
     * 发送时间戳（毫秒）
     */
    private Long timestamp;
    /**
     * 客户端操作ID（用于幂等，前端生成UUID，可选）
     */
    private String clientOpId;
}


