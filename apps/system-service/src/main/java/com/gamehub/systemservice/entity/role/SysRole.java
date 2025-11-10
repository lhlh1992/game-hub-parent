package com.gamehub.systemservice.entity.role;

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
 * 角色表实体
 * 对应数据库表：sys_role
 */
@Entity
@Table(name = "sys_role")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 角色编码（唯一标识，必须非空）
     */
    @Column(name = "role_code", nullable = false, unique = true, length = 50)
    private String roleCode;

    /**
     * 角色名称（必须非空）
     */
    @Column(name = "role_name", nullable = false, length = 50)
    private String roleName;

    /**
     * 角色描述
     */
    @Column(name = "role_desc", length = 200)
    private String roleDesc;

    /**
     * 数据权限范围：ALL（全部）、DEPT（本部门）、DEPT_AND_CHILD（本部门及子部门）、SELF（仅自己）
     */
    @Column(name = "data_scope", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DataScope dataScope = DataScope.ALL;

    /**
     * 排序号
     */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

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
     * 数据权限范围枚举
     */
    public enum DataScope {
        ALL,              // 全部数据
        DEPT,             // 本部门数据
        DEPT_AND_CHILD,   // 本部门及子部门数据
        SELF              // 仅自己数据
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

