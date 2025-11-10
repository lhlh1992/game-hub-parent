package com.gamehub.systemservice.repository.role;

import com.gamehub.systemservice.entity.role.SysRolePermission;
import com.gamehub.systemservice.entity.role.SysRolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 角色权限关联 Repository
 */
@Repository
public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, SysRolePermissionId> {

    /**
     * 根据角色ID查询所有权限ID
     */
    @Query("SELECT rp.permissionId FROM SysRolePermission rp WHERE rp.roleId = :roleId")
    List<UUID> findPermissionIdsByRoleId(@Param("roleId") UUID roleId);

    /**
     * 根据权限ID查询所有角色ID
     */
    @Query("SELECT rp.roleId FROM SysRolePermission rp WHERE rp.permissionId = :permissionId")
    List<UUID> findRoleIdsByPermissionId(@Param("permissionId") UUID permissionId);

    /**
     * 删除角色的所有权限
     */
    void deleteByRoleId(UUID roleId);

    /**
     * 删除权限的所有角色关联
     */
    void deleteByPermissionId(UUID permissionId);
}

