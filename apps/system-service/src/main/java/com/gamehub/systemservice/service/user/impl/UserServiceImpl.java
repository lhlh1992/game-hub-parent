package com.gamehub.systemservice.service.user.impl;

import com.gamehub.systemservice.dto.response.UserInfo;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.entity.user.SysUserProfile;
import com.gamehub.systemservice.exception.BusinessException;
import com.gamehub.systemservice.repository.user.SysUserProfileRepository;
import com.gamehub.systemservice.repository.user.SysUserRepository;
import com.gamehub.systemservice.service.user.UserService;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserRepository userRepository;
    private final SysUserProfileRepository userProfileRepository;
    private final Keycloak keycloak;
    
    @Value("${keycloak.realm:my-realm}")
    private String realm;

    @Override
    public Optional<SysUser> findByKeycloakUserId(UUID keycloakUserId) {
        return userRepository.findByKeycloakUserIdAndNotDeleted(keycloakUserId);
    }

    @Override
    public Optional<SysUser> findById(UUID userId) {
        return userRepository.findById(userId)
                .filter(user -> user.getDeletedAt() == null);
    }

    @Override
    @Transactional
    public SysUser syncUser(UUID keycloakUserId, String username, String email) {
        // 查询是否已存在
        Optional<SysUser> existingUser = userRepository.findByKeycloakUserIdAndNotDeleted(keycloakUserId);

        if (existingUser.isPresent()) {
            // 更新现有用户
            SysUser user = existingUser.get();
            if (username != null && !username.equals(user.getUsername())) {
                // 检查用户名是否被其他用户使用
                if (userRepository.existsByUsernameAndNotDeleted(username) && 
                    !username.equals(user.getUsername())) {
                    throw new BusinessException("用户名已被使用: " + username);
                }
                user.setUsername(username);
            }
            if (email != null) {
                user.setEmail(email);
            }
            log.info("更新用户: keycloakUserId={}, username={}", keycloakUserId, username);
            return userRepository.save(user);
        } else {
            // 创建新用户
            // 检查用户名是否已存在
            if (userRepository.existsByUsernameAndNotDeleted(username)) {
                throw new BusinessException("用户名已被使用: " + username);
            }

            SysUser user = SysUser.builder()
                    .keycloakUserId(keycloakUserId)
                    .username(username)
                    .email(email)
                    .userType(SysUser.UserType.NORMAL)
                    .status(1)
                    .build();

            user = userRepository.save(user);
            log.info("创建新用户: keycloakUserId={}, username={}, userId={}", 
                    keycloakUserId, username, user.getId());

            // 创建用户扩展信息
            SysUserProfile profile = SysUserProfile.builder()
                    .userId(user.getId())
                    .locale("zh-CN")
                    .timezone("Asia/Shanghai")
                    .settings(Map.of())
                    .build();
            userProfileRepository.save(profile);

            return user;
        }
    }

    @Override
    public Optional<SysUser> findByUsername(String username) {
        return userRepository.findByUsernameAndNotDeleted(username);
    }

    @Override
    @Transactional
    public SysUser createUser(String username, String nickname, String password, String email) {
        // 1. 检查用户名是否已存在（系统数据库）
        if (userRepository.existsByUsernameAndNotDeleted(username)) {
            throw new BusinessException("用户名已被使用: " + username);
        }

        // 2. 检查 Keycloak 中是否已存在该用户名
        RealmResource realmResource = keycloak.realm(realm);
        UsersResource usersResource = realmResource.users();
        List<UserRepresentation> existingUsers = usersResource.searchByUsername(username, true);
        if (!existingUsers.isEmpty()) {
            throw new BusinessException("Keycloak 中已存在该用户名: " + username);
        }

        // 3. 在 Keycloak 中创建用户
        UserRepresentation keycloakUser = new UserRepresentation();
        keycloakUser.setUsername(username);
        keycloakUser.setEmail(email);
        keycloakUser.setFirstName(nickname != null ? nickname : username);
        keycloakUser.setLastName("用户"); // 设置默认 lastname，满足 Keycloak 要求
        keycloakUser.setEnabled(true);
        keycloakUser.setEmailVerified(false);

        // 创建 Keycloak 用户
        Response response = usersResource.create(keycloakUser);
        if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
            String errorMessage = response.readEntity(String.class);
            log.error("在 Keycloak 中创建用户失败: status={}, error={}", response.getStatus(), errorMessage);
            throw new BusinessException("在 Keycloak 中创建用户失败: " + errorMessage);
        }

        // 获取创建的 Keycloak 用户ID（从 Location header 中提取）
        String location = response.getLocation().toString();
        String keycloakUserIdStr = location.substring(location.lastIndexOf('/') + 1);
        UUID keycloakUserId = UUID.fromString(keycloakUserIdStr);
        log.info("在 Keycloak 中创建用户成功: keycloakUserId={}, username={}", keycloakUserId, username);

        // 4. 设置 Keycloak 用户密码
        try {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(password);
            credential.setTemporary(false); // 密码不是临时的
            usersResource.get(keycloakUserIdStr).resetPassword(credential);
            log.info("设置 Keycloak 用户密码成功: keycloakUserId={}", keycloakUserId);
        } catch (Exception e) {
            log.error("设置 Keycloak 用户密码失败，尝试删除已创建的用户", e);
            // 如果设置密码失败，尝试删除已创建的 Keycloak 用户
            try {
                usersResource.get(keycloakUserIdStr).remove();
            } catch (Exception deleteEx) {
                log.error("删除 Keycloak 用户失败", deleteEx);
            }
            throw new BusinessException("设置用户密码失败: " + e.getMessage());
        }

        // 5. 在系统数据库中创建用户
        try {
            SysUser user = SysUser.builder()
                    .keycloakUserId(keycloakUserId)
                    .username(username)
                    .nickname(nickname)
                    .email(email)
                    .userType(SysUser.UserType.NORMAL)
                    .status(1)
                    .build();

            user = userRepository.save(user);
            log.info("在系统数据库中创建用户成功: userId={}, keycloakUserId={}, username={}", 
                    user.getId(), keycloakUserId, username);

            // 6. 创建用户扩展信息
            SysUserProfile profile = SysUserProfile.builder()
                    .userId(user.getId())
                    .locale("zh-CN")
                    .timezone("Asia/Shanghai")
                    .settings(Map.of())
                    .build();
            userProfileRepository.save(profile);

            return user;
        } catch (Exception e) {
            log.error("在系统数据库中创建用户失败，尝试删除 Keycloak 用户", e);
            // 如果系统数据库创建失败，尝试删除 Keycloak 用户
            try {
                usersResource.get(keycloakUserIdStr).remove();
            } catch (Exception deleteEx) {
                log.error("删除 Keycloak 用户失败", deleteEx);
            }
            throw new BusinessException("在系统数据库中创建用户失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public SysUser updateUser(UUID userId, String nickname, String password, String email) {
        // 1. 查询用户是否存在
        SysUser user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException("用户不存在或已被删除"));

        // 2. 更新昵称（需要同时更新 Keycloak 的 firstname 和系统数据库）
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname);
            
            // 同步更新 Keycloak 的 firstname
            try {
                RealmResource realmResource = keycloak.realm(realm);
                UserRepresentation keycloakUser = realmResource.users()
                        .get(user.getKeycloakUserId().toString())
                        .toRepresentation();
                keycloakUser.setFirstName(nickname);
                // lastname 保持默认值 "用户"，不需要更新
                realmResource.users().get(user.getKeycloakUserId().toString()).update(keycloakUser);
                log.info("更新 Keycloak 用户 firstname 成功: keycloakUserId={}, firstname={}", 
                        user.getKeycloakUserId(), nickname);
            } catch (Exception e) {
                log.error("更新 Keycloak 用户 firstname 失败: keycloakUserId={}", user.getKeycloakUserId(), e);
                throw new BusinessException("更新 Keycloak 用户昵称失败: " + e.getMessage());
            }
        }

        // 3. 更新邮箱（需要同时更新 Keycloak 和系统数据库）
        if (email != null && !email.isBlank()) {
            // 检查邮箱是否被其他用户使用
            Optional<SysUser> existingUser = userRepository.findByEmailAndNotDeleted(email);
            if (existingUser.isPresent() && !existingUser.get().getId().equals(userId)) {
                throw new BusinessException("邮箱已被其他用户使用: " + email);
            }

            // 更新系统数据库
            user.setEmail(email);

            // 更新 Keycloak
            try {
                RealmResource realmResource = keycloak.realm(realm);
                UserRepresentation keycloakUser = realmResource.users()
                        .get(user.getKeycloakUserId().toString())
                        .toRepresentation();
                keycloakUser.setEmail(email);
                realmResource.users().get(user.getKeycloakUserId().toString()).update(keycloakUser);
                log.info("更新 Keycloak 用户邮箱成功: keycloakUserId={}, email={}", 
                        user.getKeycloakUserId(), email);
            } catch (Exception e) {
                log.error("更新 Keycloak 用户邮箱失败: keycloakUserId={}", user.getKeycloakUserId(), e);
                throw new BusinessException("更新 Keycloak 用户邮箱失败: " + e.getMessage());
            }
        }

        // 4. 更新密码（只更新 Keycloak）
        if (password != null && !password.isBlank()) {
            try {
                RealmResource realmResource = keycloak.realm(realm);
                CredentialRepresentation credential = new CredentialRepresentation();
                credential.setType(CredentialRepresentation.PASSWORD);
                credential.setValue(password);
                credential.setTemporary(false);
                realmResource.users().get(user.getKeycloakUserId().toString())
                        .resetPassword(credential);
                log.info("更新 Keycloak 用户密码成功: keycloakUserId={}", user.getKeycloakUserId());
            } catch (Exception e) {
                log.error("更新 Keycloak 用户密码失败: keycloakUserId={}", user.getKeycloakUserId(), e);
                throw new BusinessException("更新用户密码失败: " + e.getMessage());
            }
        }

        // 5. 保存系统数据库更新
        user = userRepository.save(user);
        log.info("更新用户成功: userId={}, nickname={}, email={}", 
                userId, nickname, email != null ? "已更新" : "未更新");

        return user;
    }

    @Override
    @Transactional
    public void deleteUser(UUID userId) {
        // 1. 查询用户是否存在
        SysUser user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException("用户不存在或已被删除"));

        // 2. 在 Keycloak 中禁用用户（不删除，保留数据）
        try {
            RealmResource realmResource = keycloak.realm(realm);
            UserRepresentation keycloakUser = realmResource.users()
                    .get(user.getKeycloakUserId().toString())
                    .toRepresentation();
            keycloakUser.setEnabled(false);
            realmResource.users().get(user.getKeycloakUserId().toString()).update(keycloakUser);
            log.info("在 Keycloak 中禁用用户成功: keycloakUserId={}", user.getKeycloakUserId());
        } catch (Exception e) {
            log.error("在 Keycloak 中禁用用户失败: keycloakUserId={}", user.getKeycloakUserId(), e);
            // 即使 Keycloak 禁用失败，也继续执行软删除
            log.warn("Keycloak 禁用用户失败，但继续执行系统数据库软删除");
        }

        // 3. 在系统数据库中软删除并禁用
        user.setDeletedAt(java.time.OffsetDateTime.now());
        user.setStatus(0); // 设置为禁用状态，与 Keycloak 保持一致
        userRepository.save(user);
        log.info("软删除用户成功: userId={}, keycloakUserId={}, status=0", userId, user.getKeycloakUserId());
    }

    @Override
    public List<UserInfo> findUserInfosByKeycloakUserIds(List<String> keycloakUserIds) {
        if (keycloakUserIds == null || keycloakUserIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 将 String 格式的 keycloakUserId 转换为 UUID
        List<UUID> uuidList = keycloakUserIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> {
                    try {
                        return UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        log.warn("无效的 Keycloak 用户ID格式: {}", id);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        if (uuidList.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 批量查询用户（需要自定义查询方法）
        java.util.List<SysUser> users = userRepository.findByKeycloakUserIdInAndNotDeleted(uuidList);

        if (users.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 批量查询用户扩展信息（sys_user_profile），避免 N+1 查询
        java.util.List<UUID> userIds = users.stream()
                .map(SysUser::getId)
                .filter(Objects::nonNull)
                .toList();

        java.util.Map<UUID, SysUserProfile> profileMap = userIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : userProfileRepository.findAllById(userIds).stream()
                    .collect(java.util.stream.Collectors.toMap(SysUserProfile::getUserId, p -> p));

        // 转换为 UserInfo DTO（基础信息 + 扩展信息，游戏统计信息后续补充）
        return users.stream()
                .map(user -> {
                    SysUserProfile profile = profileMap.get(user.getId());
                    return com.gamehub.systemservice.dto.response.UserInfo.builder()
                            // 用户基础信息（来自 sys_user 表）
                            .userId(user.getKeycloakUserId().toString())
                            .systemUserId(user.getId())
                            .username(user.getUsername())
                            .nickname(user.getNickname())
                            .avatarUrl(user.getAvatarUrl())
                            .email(user.getEmail())
                            .phone(user.getPhone())
                            .userType(user.getUserType() != null ? user.getUserType().name() : "NORMAL")
                            .status(user.getStatus())
                            // 用户扩展信息（来自 sys_user_profile 表，可能为 null）
                            .bio(profile != null ? profile.getBio() : null)
                            .locale(profile != null ? profile.getLocale() : null)
                            .timezone(profile != null ? profile.getTimezone() : null)
                            .settings(profile != null ? profile.getSettings() : null)
                            // 游戏统计信息（来自 user_score 表，暂时为 null，等实体类创建后补充）
                            .levelId(null)
                            .levelName(null)
                            .levelNumber(null)
                            .totalScore(null)
                            .currentScore(null)
                            .frozenScore(null)
                            .experiencePoints(null)
                            .winCount(null)
                            .loseCount(null)
                            .drawCount(null)
                            .totalMatches(null)
                            .winRate(null)
                            .highestScore(null)
                            .build();
                })
                .toList();
    }
}

