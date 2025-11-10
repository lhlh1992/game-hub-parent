package com.gamehub.systemservice.entity.role;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户角色关联表实体
 * 对应数据库表：sys_user_role
 */
@Entity
@Table(name = "sys_user_role")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(SysUserRoleId.class)
public class SysUserRole {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}

