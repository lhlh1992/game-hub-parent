package com.gamehub.gateway.service;

import com.gamehub.gateway.config.KeycloakAdminProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Keycloak SSO 注销服务。
 *
 * 所有在网关侧被判定失效的 token，都会通过这里同步告知 Keycloak，
 * 确保浏览器无法依赖 Keycloak 原有的 SSO 会话静默登录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakSsoLogoutService {

    private final Keycloak keycloak;
    private final KeycloakAdminProperties properties;

    /**
     * 注销 Keycloak 中的用户 SSO 会话。
     *
     * @param userId         Keycloak 用户 ID（sub）
     * @param loginSessionId Keycloak SSO 会话 ID（sid/session_state，可为空）
     */
    public void logout(String userId, String loginSessionId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        try {
            RealmResource realmResource = keycloak.realm(properties.getRealm());

            // TODO：如果要精确按 loginSessionId 注销，可在此调用 admin REST:
            // DELETE /admin/realms/{realm}/sessions/{id}
            // 目前为了简单可靠，直接调用用户 logout（会注销该用户在该 realm 下的所有会话）
            realmResource.users().get(userId).logout();

            log.info("已执行 Keycloak SSO 注销: realm={}, userId={}, loginSessionId={}",
                    properties.getRealm(), userId, loginSessionId);
        } catch (Exception ex) {
            log.error("Keycloak SSO 注销失败: realm={}, userId={}, loginSessionId={}",
                    properties.getRealm(), userId, loginSessionId, ex);
        }
    }
}

