package com.gamehub.systemservice.entity.friend;

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
 * 好友关系表实体
 * 对应数据库表：user_friend
 */
@Entity
@Table(name = "user_friend", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "friend_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFriend {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 用户ID
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 好友用户ID
     */
    @Column(name = "friend_id", nullable = false)
    private UUID friendId;

    /**
     * 好友备注昵称
     */
    @Column(name = "friend_nickname", length = 50)
    private String friendNickname;

    /**
     * 好友分组
     */
    @Column(name = "friend_group", length = 50)
    private String friendGroup;

    /**
     * 关系状态：ACTIVE（正常）、BLOCKED（已拉黑）
     */
    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private FriendStatus status = FriendStatus.ACTIVE;

    /**
     * 是否特别关心
     */
    @Column(name = "is_favorite", nullable = false)
    @Builder.Default
    private Boolean isFavorite = false;

    /**
     * 最后互动时间
     */
    @Column(name = "last_interaction_time")
    private OffsetDateTime lastInteractionTime;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * 关系状态枚举
     */
    public enum FriendStatus {
        ACTIVE,   // 正常
        BLOCKED   // 已拉黑
    }
}

