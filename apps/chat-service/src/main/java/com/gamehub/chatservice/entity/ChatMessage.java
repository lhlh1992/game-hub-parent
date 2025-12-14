package com.gamehub.chatservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天消息表实体
 * 对应数据库表：chat_message
 */
@Entity
@Table(name = "chat_message", indexes = {
    @Index(name = "idx_chat_message_session", columnList = "session_id"),
    @Index(name = "idx_chat_message_sender", columnList = "sender_id"),
    @Index(name = "idx_chat_message_session_time", columnList = "session_id,created_at"),
    @Index(name = "idx_chat_message_reply", columnList = "reply_to_message_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_chat_message_client_op", columnNames = "client_op_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 会话ID
     */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /**
     * 发送者用户ID（Keycloak用户ID，UUID格式）
     */
    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    /**
     * 客户端操作ID（用于幂等，前端生成UUID）
     */
    @Column(name = "client_op_id", length = 64)
    private String clientOpId;

    /**
     * 消息类型：TEXT（文本）、IMAGE（图片）、FILE（文件）、SYSTEM（系统消息）
     */
    @Column(name = "message_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private MessageType messageType = MessageType.TEXT;

    /**
     * 消息内容
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 扩展数据（JSONB，如：图片URL、文件信息等）
     */
    @Column(name = "extra_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private Map<String, Object> extraData = Map.of();

    /**
     * 回复的消息ID
     */
    @Column(name = "reply_to_message_id")
    private UUID replyToMessageId;

    /**
     * 是否已撤回
     */
    @Column(name = "is_recalled", nullable = false)
    @Builder.Default
    private Boolean isRecalled = false;

    /**
     * 撤回时间
     */
    @Column(name = "recalled_at")
    private OffsetDateTime recalledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        TEXT,    // 文本
        IMAGE,   // 图片
        FILE,    // 文件
        SYSTEM   // 系统消息
    }
}

