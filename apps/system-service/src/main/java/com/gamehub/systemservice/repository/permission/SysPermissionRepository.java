package com.gamehub.systemservice.repository.permission;

import com.gamehub.systemservice.entity.permission.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 权限 Repository
 */
@Repository
public interface SysPermissionRepository extends JpaRepository<SysPermission, UUID> {

    /**
     * 根据权限编码查询（未删除）
     */
    @Query("SELECT p FROM SysPermission p WHERE p.permissionCode = :permissionCode AND p.deletedAt IS NULL")
    Optional<SysPermission> findByPermissionCodeAndNotDeleted(@Param("permissionCode") String permissionCode);

    /**
     * 根据权限类型查询（未删除）
     */
    @Query("SELECT p FROM SysPermission p WHERE p.permissionType = :permissionType AND p.deletedAt IS NULL ORDER BY p.sortOrder ASC")
    List<SysPermission> findByPermissionTypeAndNotDeleted(@Param("permissionType") SysPermission.PermissionType permissionType);

    /**
     * 查询所有启用的权限（未删除）
     */
    @Query("SELECT p FROM SysPermission p WHERE p.status = 1 AND p.deletedAt IS NULL ORDER BY p.sortOrder ASC")
    List<SysPermission> findAllEnabled();

    /**
     * 检查权限编码是否存在（未删除）
     */
    @Query("SELECT COUNT(p) > 0 FROM SysPermission p WHERE p.permissionCode = :permissionCode AND p.deletedAt IS NULL")
    boolean existsByPermissionCodeAndNotDeleted(@Param("permissionCode") String permissionCode);
}

