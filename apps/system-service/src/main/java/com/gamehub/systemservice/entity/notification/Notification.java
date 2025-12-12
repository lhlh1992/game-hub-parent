package com.gamehub.systemservice.entity.notification;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户通知实体（用于离线/未读存储）。
 */
@Entity
@Table(name = "user_notification")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 目标用户（系统用户ID）。
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 通知类型：INFO / FRIEND_REQUEST 等。
     */
    @Column(name = "type", length = 50, nullable = false)
    private String type;

    /**
     * 标题。
     */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /**
     * 内容。
     */
    @Column(name = "content", length = 500, nullable = false)
    private String content;

    /**
     * 触发方用户（可选，Keycloak userId）。
     */
    @Column(name = "from_user_id", length = 64)
    private String fromUserId;

    /**
     * 关联业务 ID（如好友申请 ID）。
     */
    @Column(name = "ref_id")
    private UUID refId;

    /**
     * 未读/已读。
     */
    @Column(name = "status", length = 10, nullable = false)
    @Builder.Default
    private String status = "UNREAD";

    /**
     * 创建时间。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}

