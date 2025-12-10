package com.gamehub.chatservice.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 房间/大厅聊天消息载体（用于 WS 推送与历史存储）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessagePayload {
    /**
     * 消息类型：LOBBY / ROOM
     */
    private String type;
    /**
     * 房间 ID（大厅为 null）
     */
    private String roomId;
    /**
     * 发送者用户 ID
     */
    private String senderId;
    /**
     * 发送者昵称（从缓存解析，兜底 userId）
     */
    private String senderName;
    /**
     * 文本内容（已做长度截断/去空白）
     */
    private String content;
    /**
     * 发送时间戳（毫秒）
     */
    private Long timestamp;
}


