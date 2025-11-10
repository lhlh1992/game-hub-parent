package com.gamehub.systemservice.service.user;

import com.gamehub.systemservice.entity.user.SysUser;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 根据 Keycloak 用户ID查询系统用户
     */
    Optional<SysUser> findByKeycloakUserId(UUID keycloakUserId);

    /**
     * 根据系统用户ID查询
     */
    Optional<SysUser> findById(UUID userId);

    /**
     * 同步用户（从 Keycloak 同步到系统用户表）
     * 如果用户不存在则创建，存在则更新
     */
    SysUser syncUser(UUID keycloakUserId, String username, String email);

    /**
     * 根据用户名查询
     */
    Optional<SysUser> findByUsername(String username);

    /**
     * 创建用户（同时在 Keycloak 和系统数据库中创建）
     * @param username 用户名
     * @param nickname 昵称
     * @param password 密码
     * @param email 邮箱
     * @return 创建的系统用户
     */
    SysUser createUser(String username, String nickname, String password, String email);

    /**
     * 更新用户信息
     * @param userId 用户ID
     * @param nickname 昵称（可选）
     * @param password 密码（可选）
     * @param email 邮箱（可选）
     * @return 更新后的系统用户
     */
    SysUser updateUser(UUID userId, String nickname, String password, String email);

    /**
     * 删除用户（软删除）
     * @param userId 用户ID
     */
    void deleteUser(UUID userId);
}
