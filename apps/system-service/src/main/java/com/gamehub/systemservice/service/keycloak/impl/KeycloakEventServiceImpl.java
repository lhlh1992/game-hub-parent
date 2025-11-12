package com.gamehub.systemservice.service.keycloak.impl;

import com.gamehub.systemservice.dto.keycloak.KeycloakEventPayload;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.repository.user.SysUserRepository;
import com.gamehub.systemservice.service.keycloak.KeycloakEventService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakEventServiceImpl implements KeycloakEventService {

    private final Keycloak keycloak;
    private final SysUserRepository userRepository;

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
            username = event.getDetails().get("username");
            email = event.getDetails().get("email");
            String firstName = event.getDetails().get("first_name");
            
            // 昵称直接使用 firstName
            if (firstName != null && !firstName.isBlank()) {
                nickname = firstName;
            }
        }

        // 如果 details 中没有足够信息，才调用 Keycloak Admin API 获取
        if (username == null || email == null) {
            log.debug("事件 details 信息不完整，调用 Keycloak Admin API 获取用户信息");
            try {
                UserRepresentation kcUser = keycloak.realm(realm).users().get(keycloakUserId).toRepresentation();
                if (kcUser != null) {
                    if (username == null) username = kcUser.getUsername();
                    if (email == null) email = kcUser.getEmail();
                    if (nickname == null) {
                        nickname = kcUser.getFirstName() != null && !kcUser.getFirstName().isBlank()
                                ? kcUser.getFirstName()
                                : username;
                    }
                }
            } catch (Exception ex) {
                log.warn("调用 Keycloak Admin API 获取用户信息失败: {}", ex.getMessage());
            }
        }

        // 如果仍然没有足够信息，记录警告并跳过
        if (username == null || username.isBlank()) {
            log.warn("无法获取用户名，跳过创建: keycloakUserId={}", keycloakUserId);
            return;
        }

        // 创建本地用户
        SysUser user = SysUser.builder()
                .keycloakUserId(kcUserUuid)
                .username(username)
                .email(email != null ? email : username)  // email 可以为空，用 username 兜底
                .nickname(nickname != null ? nickname : username)  // nickname 用 username 兜底
                .status(1)  // 注册事件默认启用
                .build();
        userRepository.save(user);
        log.info("创建本地用户成功: keycloakUserId={}, localUserId={}, username={}, email={}", 
                keycloakUserId, user.getId(), username, email);
    }
}





