package com.gamehub.systemservice.entity.permission;

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
 * 权限表实体
 * 对应数据库表：sys_permission
 */
@Entity
@Table(name = "sys_permission")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 权限编码（唯一标识，必须非空）
     */
    @Column(name = "permission_code", nullable = false, unique = true, length = 100)
    private String permissionCode;

    /**
     * 权限名称（必须非空）
     */
    @Column(name = "permission_name", nullable = false, length = 100)
    private String permissionName;

    /**
     * 权限类型：MENU（菜单）、BUTTON（按钮）、API（接口）
     */
    @Column(name = "permission_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PermissionType permissionType;

    /**
     * 资源类型
     */
    @Column(name = "resource_type", length = 50)
    private String resourceType;

    /**
     * 资源路径
     */
    @Column(name = "resource_path", length = 500)
    private String resourcePath;

    /**
     * HTTP方法（GET、POST、PUT、DELETE等）
     */
    @Column(name = "http_method", length = 10)
    @Enumerated(EnumType.STRING)
    private HttpMethod httpMethod;

    /**
     * 数据权限表达式（JSONB）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_expr", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> dataExpr = Map.of();

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
     * 权限类型枚举
     */
    public enum PermissionType {
        MENU,    // 菜单权限
        BUTTON,  // 按钮权限
        API      // 接口权限
    }

    /**
     * HTTP方法枚举
     */
    public enum HttpMethod {
        GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
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

