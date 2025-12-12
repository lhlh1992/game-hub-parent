package com.gamehub.systemservice.entity.notification;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全局通知表实体：映射 sys_notification。
 */
@Entity
@Table(name = "sys_notification")
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
     * 通知类型：FRIEND_REQUEST、SYSTEM_ALERT 等。
     */
    @Column(name = "type", length = 50, nullable = false)
    private String type;

    /**
     * 标题。
     */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /**
     * 文案内容。
     */
    @Column(name = "content", length = 500, nullable = false)
    private String content;

    /**
     * 触发方用户（Keycloak userId，可选）。
     */
    @Column(name = "from_user_id", length = 64)
    private String fromUserId;

    /**
     * 关联业务类型/ID，便于幂等去重与跳转。
     */
    @Column(name = "ref_type", length = 50)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    /**
     * 透传数据（jsonb）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = Map.of();

    /**
     * 可操作按钮列表。
     */
    @Column(name = "actions", columnDefinition = "text[]")
    private List<String> actions;

    /**
     * 状态：UNREAD / READ / ARCHIVED / DELETED。
     */
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = "UNREAD";

    /**
     * 来源服务标识。
     */
    @Column(name = "source_service", length = 50)
    private String sourceService;

    /**
     * 创建/读取/归档/删除时间。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}


