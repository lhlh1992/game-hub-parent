package com.gamehub.systemservice.service.keycloak.impl;

import com.gamehub.systemservice.dto.keycloak.KeycloakEventPayload;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.entity.user.SysUserProfile;
import com.gamehub.systemservice.repository.user.SysUserProfileRepository;
import com.gamehub.systemservice.repository.user.SysUserRepository;
import com.gamehub.systemservice.service.keycloak.KeycloakEventService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakEventServiceImpl implements KeycloakEventService {

    private final Keycloak keycloak;
    private final SysUserRepository userRepository;
    private final SysUserProfileRepository userProfileRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${keycloak.realm:my-realm}")
    private String realm;

    @Override
    @Transactional
    public void handleEvent(KeycloakEventPayload payload) {
        if (payload == null) {
            return;
        }
        
        // 只处理用户注册事件
        if (payload.getEvent() != null) {
            KeycloakEventPayload.Event event = payload.getEvent();
            String type = event.getType();
            String userId = event.getUserId();
            
            // 只处理 REGISTER 事件
            if ("REGISTER".equalsIgnoreCase(type)) {
                log.info("处理用户注册事件: userId={}, realmId={}", userId, event.getRealmId());
                createLocalUser(event);
            } else {
                log.debug("忽略非注册事件: type={}, userId={}", type, userId);
            }
        } else {
            log.debug("收到非用户事件，忽略处理");
        }
    }

    /**
     * 创建本地用户（仅用于注册事件）
     * 优先使用事件 details 中的信息，避免额外调用 Keycloak Admin API
     */
    private void createLocalUser(KeycloakEventPayload.Event event) {
        String keycloakUserId = event.getUserId();
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            log.warn("事件缺少 userId，跳过创建");
            return;
        }

        UUID kcUserUuid;
        try {
            kcUserUuid = UUID.fromString(keycloakUserId);
        } catch (IllegalArgumentException ex) {
            log.warn("Keycloak userId 不是有效的 UUID: {}", keycloakUserId);
            return;
        }

        // 检查是否已存在（幂等性保护）
        Optional<SysUser> existed = userRepository.findByKeycloakUserIdAndNotDeleted(kcUserUuid);
        if (existed.isPresent()) {
            log.info("用户已存在，跳过创建: keycloakUserId={}, localUserId={}", keycloakUserId, existed.get().getId());
            return;
        }

        // 优先使用事件 details 中的信息（vymalo/keycloak-webhook 插件会在 details 中包含用户信息）
        String username = null;
        String email = null;
        String nickname = null;
        
        if (event.getDetails() != null) {
            String detailsUsername = event.getDetails().get("username");
            String detailsEmail = event.getDetails().get("email");
            // 检查空字符串，只有非空才使用
            if (detailsUsername != null && !detailsUsername.isBlank()) {
                username = detailsUsername;
            }
            if (detailsEmail != null && !detailsEmail.isBlank()) {
                email = detailsEmail;
            }
            // 尝试多种可能的字段名（不同插件可能使用不同的命名方式）
            String firstName = event.getDetails().get("firstName");  // 驼峰命名
            if (firstName == null || firstName.isBlank()) {
                firstName = event.getDetails().get("first_name");  // 下划线命名
            }
            if (firstName == null || firstName.isBlank()) {
                firstName = event.getDetails().get("firstname");  // 全小写
            }
            // 昵称直接使用 firstName
            if (firstName != null && !firstName.isBlank()) {
                nickname = firstName;
            }
        }

        // 如果 details 中没有足够信息，或者缺少昵称，调用 Keycloak Admin API 获取完整信息
        // 关键修复：即使 details 中有 username 和 email，如果 nickname 为 null，也要调用 Admin API 获取昵称
        boolean needUsername = (username == null || username.isBlank());
        boolean needEmail = (email == null || email.isBlank());
        boolean needNickname = (nickname == null || nickname.isBlank());
        
        if (needUsername || needEmail || needNickname) {
            log.debug("事件 details 信息不完整（username={}, email={}, nickname={}），调用 Keycloak Admin API 获取用户信息", 
                    !needUsername, !needEmail, !needNickname);
            
            // 添加短暂延迟，确保 Keycloak 已保存用户信息（特别是 firstName）
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            
            try {
                UserRepresentation kcUser = keycloak.realm(realm).users().get(keycloakUserId).toRepresentation();
                if (kcUser != null) {
                    if (needUsername) {
                        username = kcUser.getUsername();
                    }
                    if (needEmail) {
                        email = kcUser.getEmail();
                    }
                    // 关键修复：如果 nickname 为 null 或空，从 Admin API 获取
                    if (needNickname) {
                        nickname = kcUser.getFirstName() != null && !kcUser.getFirstName().isBlank()
                                ? kcUser.getFirstName()
                                : (username != null && !username.isBlank() ? username : null);
                        log.debug("从 Keycloak Admin API 获取到昵称: firstName={}, nickname={}", 
                                kcUser.getFirstName(), nickname);
                    }
                }
            } catch (Exception ex) {
                log.error("调用 Keycloak Admin API 获取用户信息失败: keycloakUserId={}", keycloakUserId, ex);
            }
        }

        // 如果仍然没有足够信息，记录警告并跳过
        if (username == null || username.isBlank()) {
            log.error("无法获取用户名，跳过创建: keycloakUserId={}, username={}, email={}, nickname={}", 
                    keycloakUserId, username, email, nickname);
            return;
        }

        // 确保 nickname 有值（优先使用 firstName，否则使用 username）
        if (nickname == null || nickname.isBlank()) {
            nickname = username;
            log.debug("昵称为空，使用用户名作为昵称: username={}", username);
        }

        // 生成玩家ID
        Long playerId = generatePlayerIdFromSequence();

        // 创建本地用户
        SysUser user = SysUser.builder()
                .keycloakUserId(kcUserUuid)
                .username(username)
                .email(email != null && !email.isBlank() ? email : username)  // email 可以为空，用 username 兜底
                .nickname(nickname)  // 确保 nickname 有值
                .playerId(playerId)  // 设置玩家ID
                .userType(SysUser.UserType.NORMAL)  // 默认普通用户
                .status(1)  // 注册事件默认启用
                .build();
        
        user = userRepository.save(user);
        
        // 创建用户扩展信息
        SysUserProfile profile = SysUserProfile.builder()
                .userId(user.getId())
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .settings(Map.of())
                .build();
        userProfileRepository.save(profile);
        
        log.info("创建本地用户成功: keycloakUserId={}, localUserId={}, username={}, email={}, nickname={}, playerId={}", 
                keycloakUserId, user.getId(), user.getUsername(), user.getEmail(), user.getNickname(), user.getPlayerId());
    }

    /**
     * 生成全局唯一的玩家ID（使用数据库序列，避免并发撞库）
     */
    private long generatePlayerIdFromSequence() {
        Long nextVal = jdbcTemplate.queryForObject("SELECT nextval('player_id_seq')", Long.class);
        if (nextVal == null) {
            throw new RuntimeException("生成玩家ID失败：序列返回空值");
        }
        return nextVal;
    }
}





