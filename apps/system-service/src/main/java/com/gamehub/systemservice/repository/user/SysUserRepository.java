package com.gamehub.systemservice.repository.user;

import com.gamehub.systemservice.entity.user.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户 Repository
 */
@Repository
public interface SysUserRepository extends JpaRepository<SysUser, UUID> {

    /**
     * 根据 Keycloak 用户ID查询（未删除）
     */
    @Query("SELECT u FROM SysUser u WHERE u.keycloakUserId = :keycloakUserId AND u.deletedAt IS NULL")
    Optional<SysUser> findByKeycloakUserIdAndNotDeleted(@Param("keycloakUserId") UUID keycloakUserId);

    /**
     * 根据用户名查询（未删除）
     */
    @Query("SELECT u FROM SysUser u WHERE u.username = :username AND u.deletedAt IS NULL")
    Optional<SysUser> findByUsernameAndNotDeleted(@Param("username") String username);

    /**
     * 根据邮箱查询（未删除）
     */
    @Query("SELECT u FROM SysUser u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<SysUser> findByEmailAndNotDeleted(@Param("email") String email);

    /**
     * 检查 Keycloak 用户ID是否存在（未删除）
     */
    @Query("SELECT COUNT(u) > 0 FROM SysUser u WHERE u.keycloakUserId = :keycloakUserId AND u.deletedAt IS NULL")
    boolean existsByKeycloakUserIdAndNotDeleted(@Param("keycloakUserId") UUID keycloakUserId);

    /**
     * 检查用户名是否存在（未删除）
     */
    @Query("SELECT COUNT(u) > 0 FROM SysUser u WHERE u.username = :username AND u.deletedAt IS NULL")
    boolean existsByUsernameAndNotDeleted(@Param("username") String username);

    /**
     * 检查玩家ID是否存在（未删除）
     */
    @Query("SELECT COUNT(u) > 0 FROM SysUser u WHERE u.playerId = :playerId AND u.deletedAt IS NULL")
    boolean existsByPlayerIdAndNotDeleted(@Param("playerId") Long playerId);

    /**
     * 批量根据 Keycloak 用户ID查询（未删除）
     */
    @Query("SELECT u FROM SysUser u WHERE u.keycloakUserId IN :keycloakUserIds AND u.deletedAt IS NULL")
    java.util.List<SysUser> findByKeycloakUserIdInAndNotDeleted(@Param("keycloakUserIds") java.util.List<UUID> keycloakUserIds);
}

