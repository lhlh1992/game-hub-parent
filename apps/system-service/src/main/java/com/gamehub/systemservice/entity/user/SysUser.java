package com.gamehub.systemservice.entity.user;

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
 * 用户表实体
 * 对应数据库表：sys_user
 */
@Entity
@Table(name = "sys_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Keycloak 用户ID（对应 JWT 中 sub，必须非空，唯一）
     */
    @Column(name = "keycloak_user_id", nullable = false, unique = true, updatable = false)
    private UUID keycloakUserId;

    /**
     * 用户名（大小写不敏感，必须非空）
     */
    @Column(name = "username", nullable = false, length = 50)
    private String username;

    /**
     * 昵称
     */
    @Column(name = "nickname", length = 50)
    private String nickname;

    /**
     * 邮箱（大小写不敏感）
     */
    @Column(name = "email", length = 100)
    private String email;

    /**
     * 手机号
     */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * 头像URL
     */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /**
     * 用户类型：NORMAL（普通用户）、ADMIN（管理员）
     */
    @Column(name = "user_type", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserType userType = UserType.NORMAL;

    /**
     * 部门ID
     */
    @Column(name = "dept_id")
    private UUID deptId;

    /**
     * 状态：0-禁用，1-启用
     */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Integer status = 1;

    /**
     * 备注
     */
    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 玩家ID（唯一数字ID，用于查找和分享）
     */
    @Column(name = "player_id", nullable = false, unique = true)
    private Long playerId;

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
     * 软删除时间（NULL 表示未删除）
     */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * 用户类型枚举
     */
    public enum UserType {
        NORMAL,  // 普通用户
        ADMIN    // 管理员
    }

    /**
     * 是否已删除
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}

