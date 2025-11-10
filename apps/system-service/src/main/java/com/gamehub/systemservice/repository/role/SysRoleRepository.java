package com.gamehub.systemservice.repository.role;

import com.gamehub.systemservice.entity.role.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 角色 Repository
 */
@Repository
public interface SysRoleRepository extends JpaRepository<SysRole, UUID> {

    /**
     * 根据角色编码查询（未删除）
     */
    @Query("SELECT r FROM SysRole r WHERE r.roleCode = :roleCode AND r.deletedAt IS NULL")
    Optional<SysRole> findByRoleCodeAndNotDeleted(@Param("roleCode") String roleCode);

    /**
     * 查询所有启用的角色（未删除）
     */
    @Query("SELECT r FROM SysRole r WHERE r.status = 1 AND r.deletedAt IS NULL ORDER BY r.sortOrder ASC")
    List<SysRole> findAllEnabled();

    /**
     * 检查角色编码是否存在（未删除）
     */
    @Query("SELECT COUNT(r) > 0 FROM SysRole r WHERE r.roleCode = :roleCode AND r.deletedAt IS NULL")
    boolean existsByRoleCodeAndNotDeleted(@Param("roleCode") String roleCode);
}

