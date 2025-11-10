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
 * 角色权限关联表实体
 * 对应数据库表：sys_role_permission
 */
@Entity
@Table(name = "sys_role_permission")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(SysRolePermissionId.class)
public class SysRolePermission {

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Id
    @Column(name = "permission_id", nullable = false, updatable = false)
    private UUID permissionId;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}

