package com.gamehub.systemservice.service.user;

import com.gamehub.systemservice.dto.response.UserInfo;
import com.gamehub.systemservice.entity.user.SysUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户领域服务接口（系统内对用户的统一入口）。
 */
public interface UserService {

    /**
     * 根据 Keycloak 用户 ID 查询系统用户（sys_user）。
     */
    Optional<SysUser> findByKeycloakUserId(UUID keycloakUserId);

    /**
     * 根据系统用户 ID 查询 sys_user。
     */
    Optional<SysUser> findById(UUID userId);

    /**
     * 同步用户（从 Keycloak 同步到系统用户表）。
     * 如果用户不存在则创建，存在则更新用户名 / 邮箱等基础信息。
     */
    SysUser syncUser(UUID keycloakUserId, String username, String email);

    /**
     * 根据用户名查询 sys_user。
     */
    Optional<SysUser> findByUsername(String username);

    /**
     * 创建用户（同时在 Keycloak 和系统数据库中创建）。
     *
     * @param username 用户名
     * @param nickname 昵称
     * @param password 密码
     * @param email    邮箱
     * @return 创建成功的系统用户
     */
    SysUser createUser(String username, String nickname, String password, String email);

    /**
     * 更新用户信息。
     *
     * @param userId   用户 ID
     * @param nickname 昵称（可选）
     * @param password 密码（可选）
     * @param email    邮箱（可选）
     * @return 更新后的系统用户
     */
    SysUser updateUser(UUID userId, String nickname, String password, String email);

    /**
     * 删除用户（软删除）。
     *
     * @param userId 用户 ID
     */
    void deleteUser(UUID userId);

    /**
     * 根据一组 Keycloak 用户 ID（JWT sub）查询完整用户信息。
     * 返回的 UserInfo 聚合了 sys_user + sys_user_profile（游戏统计后续可扩展）。
     */
    List<UserInfo> findUserInfosByKeycloakUserIds(List<String> keycloakUserIds);
}


