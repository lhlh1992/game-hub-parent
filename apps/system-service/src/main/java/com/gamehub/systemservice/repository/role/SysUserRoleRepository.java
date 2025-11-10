package com.gamehub.systemservice.repository.role;

import com.gamehub.systemservice.entity.role.SysUserRole;
import com.gamehub.systemservice.entity.role.SysUserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 用户角色关联 Repository
 */
@Repository
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, SysUserRoleId> {

    /**
     * 根据用户ID查询所有角色ID
     */
    @Query("SELECT ur.roleId FROM SysUserRole ur WHERE ur.userId = :userId")
    List<UUID> findRoleIdsByUserId(@Param("userId") UUID userId);

    /**
     * 根据角色ID查询所有用户ID
     */
    @Query("SELECT ur.userId FROM SysUserRole ur WHERE ur.roleId = :roleId")
    List<UUID> findUserIdsByRoleId(@Param("roleId") UUID roleId);

    /**
     * 删除用户的所有角色
     */
    void deleteByUserId(UUID userId);

    /**
     * 删除角色的所有用户关联
     */
    void deleteByRoleId(UUID roleId);
}

