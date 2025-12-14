package com.gamehub.chatservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 聊天会话成员表实体
 * 对应数据库表：chat_session_member
 */
@Entity
@Table(name = "chat_session_member", indexes = {
    @Index(name = "idx_session_member_session", columnList = "session_id"),
    @Index(name = "idx_session_member_user", columnList = "user_id"),
    @Index(name = "idx_session_member_user_session", columnList = "user_id,session_id")
}, uniqueConstraints = {
    @UniqueConstraint(columnNames = {"session_id", "user_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionMember {

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
     * 用户ID（Keycloak用户ID，UUID格式）
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 成员角色：MEMBER（成员）、ADMIN（管理员）、OWNER（群主）
     */
    @Column(name = "member_role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private MemberRole memberRole = MemberRole.MEMBER;

    /**
     * 在会话中的昵称
     */
    @Column(name = "nickname_in_session", length = 50)
    private String nicknameInSession;

    /**
     * 加入时间
     */
    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    /**
     * 离开时间
     */
    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    /**
     * 最后已读消息ID
     * 用于计算未读消息数：未读数 = 会话中最后一条消息ID > 该用户最后已读消息ID 的消息数量
     */
    @Column(name = "last_read_message_id")
    private UUID lastReadMessageId;

    /**
     * 最后已读时间
     */
    @Column(name = "last_read_time")
    private OffsetDateTime lastReadTime;

    /**
     * 成员角色枚举
     */
    public enum MemberRole {
        MEMBER,  // 成员
        ADMIN,   // 管理员
        OWNER    // 群主
    }
}

