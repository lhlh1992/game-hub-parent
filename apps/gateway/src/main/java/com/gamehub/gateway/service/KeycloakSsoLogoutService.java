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
            // 拿到当前配置 realm 的管理接口入口，用它调用 admin API（例如用户登出）
            RealmResource realmResource = keycloak.realm(properties.getRealm());

            if (StringUtils.hasText(loginSessionId)) {
                // 精确注销在线 SSO 会话（isOffline=false 表示只踢浏览器/在线会话）
                realmResource.deleteSession(loginSessionId, false);
                log.info("已精确注销 Keycloak SSO 会话: realm={}, userId={}, loginSessionId={}",
                        properties.getRealm(), userId, loginSessionId);
            } else {
                // 兜底：如果拿不到 loginSessionId，只能注销该用户的全部会话
                realmResource.users().get(userId).logout();
                log.info("已兜底注销 Keycloak 用户所有会话: realm={}, userId={}", properties.getRealm(), userId);
            }
        } catch (Exception ex) {
            log.error("Keycloak SSO 注销失败: realm={}, userId={}, loginSessionId={}",
                    properties.getRealm(), userId, loginSessionId, ex);
        }
    }
}

