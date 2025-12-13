package com.gamehub.chatservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 聊天会话表实体
 * 对应数据库表：chat_session
 */
@Entity
@Table(name = "chat_session", indexes = {
    @Index(name = "idx_chat_session_type", columnList = "session_type"),
    @Index(name = "idx_chat_session_room", columnList = "room_id"),
    @Index(name = "idx_chat_session_last_message", columnList = "last_message_time")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_chat_private_key", columnNames = "support_key"),
    @UniqueConstraint(name = "uk_chat_session_room_unique", columnNames = "room_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 会话类型：PRIVATE（私聊）、ROOM（房间聊天）、GROUP（群聊）
     */
    @Column(name = "session_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SessionType sessionType;

    /**
     * 会话名称（群聊时使用）
     */
    @Column(name = "session_name", length = 100)
    private String sessionName;

    /**
     * 私聊会话唯一键（格式：min(user1,user2)||'|'||max(user1,user2)）
     * 用于私聊会话去重，确保相同两个用户只能有一个PRIVATE会话
     */
    @Column(name = "support_key", length = 120)
    private String supportKey;

    /**
     * 房间ID（房间聊天时使用）
     */
    @Column(name = "room_id")
    private UUID roomId;

    /**
     * 创建人用户ID（群聊时使用）
     */
    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * 最后一条消息ID
     */
    @Column(name = "last_message_id")
    private UUID lastMessageId;

    /**
     * 最后消息时间
     */
    @Column(name = "last_message_time")
    private OffsetDateTime lastMessageTime;

    /**
     * 成员数量（必须非空，默认0）
     */
    @Column(name = "member_count", nullable = false)
    @Builder.Default
    private Integer memberCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * 会话类型枚举
     */
    public enum SessionType {
        PRIVATE,  // 私聊
        ROOM,     // 房间聊天
        GROUP     // 群聊
    }
}
