package com.gamehub.systemservice.entity.user;

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
import java.util.Map;
import java.util.UUID;

/**
 * 用户扩展表实体
 * 对应数据库表：sys_user_profile
 */
@Entity
@Table(name = "sys_user_profile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUserProfile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * 个人简介
     */
    @Column(name = "bio", length = 500)
    private String bio;

    /**
     * 语言偏好（如：zh-CN、en-US）
     */
    @Column(name = "locale", length = 10, nullable = false)
    @Builder.Default
    private String locale = "zh-CN";

    /**
     * 时区（如：Asia/Shanghai、UTC）
     */
    @Column(name = "timezone", length = 50, nullable = false)
    @Builder.Default
    private String timezone = "Asia/Shanghai";

    /**
     * 用户设置（JSONB）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> settings = Map.of();

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
}

