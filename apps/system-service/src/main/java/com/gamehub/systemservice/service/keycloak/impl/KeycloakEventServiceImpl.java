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
        if (payload.getEvent() != null) {
            handleUserEvent(payload.getEvent());
        } else if (payload.getAdminEvent() != null) {
            handleAdminEvent(payload.getAdminEvent());
        } else {
            log.warn("收到未知的 Keycloak 事件负载: {}", payload);
        }
    }

    private void handleUserEvent(KeycloakEventPayload.Event event) {
        String type = event.getType();
        String userId = event.getUserId();
        log.info("接收 Keycloak 用户事件: type={}, userId={}, realmId={}", type, userId, event.getRealmId());

        if (userId == null || userId.isBlank()) {
            return;
        }
        // 仅对 REGISTER/UPDATE_PROFILE 类事件进行同步落库
        if ("REGISTER".equalsIgnoreCase(type) || "UPDATE_PROFILE".equalsIgnoreCase(type)) {
            upsertLocalUser(userId);
        }
    }

    private void handleAdminEvent(KeycloakEventPayload.AdminEvent adminEvent) {
        String resourceType = adminEvent.getResourceType();
        String operationType = adminEvent.getOperationType();
        String resourcePath = adminEvent.getResourcePath();
        log.info("接收 Keycloak 管理事件: resourceType={}, operationType={}, resourcePath={}",
                resourceType, operationType, resourcePath);

        // 例如: users/2f4c2a1f-... 提取用户ID
        if ("USER".equalsIgnoreCase(resourceType) && resourcePath != null && resourcePath.startsWith("users/")) {
            String userId = resourcePath.substring("users/".length());
            if ("CREATE".equalsIgnoreCase(operationType) || "UPDATE".equalsIgnoreCase(operationType)) {
                upsertLocalUser(userId);
            }
            if ("DELETE".equalsIgnoreCase(operationType)) {
                softDeleteLocalUser(userId);
            }
        }
    }

    private void upsertLocalUser(String keycloakUserId) {
        UUID kcUserUuid;
        try {
            kcUserUuid = UUID.fromString(keycloakUserId);
        } catch (IllegalArgumentException ex) {
            log.warn("Keycloak userId 不是有效的 UUID: {}", keycloakUserId);
            return;
        }

        UserRepresentation kcUser = keycloak.realm(realm).users().get(keycloakUserId).toRepresentation();
        if (kcUser == null) {
            log.warn("在 Keycloak 未找到用户: {}", keycloakUserId);
            return;
        }

        String username = kcUser.getUsername();
        String email = kcUser.getEmail();
        String nickname = kcUser.getFirstName() != null && !kcUser.getFirstName().isBlank()
                ? kcUser.getFirstName()
                : username;

        Optional<SysUser> existed = userRepository.findByKeycloakUserIdAndNotDeleted(kcUserUuid);
        if (existed.isPresent()) {
            SysUser user = existed.get();
            user.setUsername(username);
            user.setEmail(email);
            user.setNickname(nickname);
            userRepository.save(user);
            log.info("更新本地用户成功: {}", user.getId());
        } else {
            SysUser user = SysUser.builder()
                    .keycloakUserId(kcUserUuid)
                    .username(username)
                    .email(email)
                    .nickname(nickname)
                    .status(Boolean.TRUE.equals(kcUser.isEnabled()) ? 1 : 0)
                    .build();
            userRepository.save(user);
            log.info("创建本地用户成功: {}", user.getId());
        }
    }

    private void softDeleteLocalUser(String keycloakUserId) {
        try {
            UUID kcUserUuid = UUID.fromString(keycloakUserId);
            userRepository.findByKeycloakUserIdAndNotDeleted(kcUserUuid).ifPresent(user -> {
                user.setStatus(0);
                user.setDeletedAt(java.time.OffsetDateTime.now());
                userRepository.save(user);
                log.info("软删除本地用户成功: {}", user.getId());
            });
        } catch (IllegalArgumentException ignored) {
            log.warn("Keycloak userId 不是有效的 UUID: {}", keycloakUserId);
        }
    }
}





