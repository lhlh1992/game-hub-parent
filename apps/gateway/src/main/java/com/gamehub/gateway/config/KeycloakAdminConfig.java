package com.gamehub.gateway.config;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keycloak 管理端客户端配置。
 *
 * 创建一个可复用的 Keycloak Admin Client（基于 admin-cli 或管理员账号），
 * 供 {@link com.gamehub.gateway.service.KeycloakSsoLogoutService} 等组件调用 Admin API，
 * 以便在本地会话失效时同步注销 Keycloak SSO 会话。
 */
@Configuration
public class KeycloakAdminConfig {

    @Bean
    public Keycloak keycloakAdminClient(KeycloakAdminProperties properties) {
        KeycloakBuilder builder = KeycloakBuilder.builder()
                .serverUrl(properties.getServerUrl())
                .realm(properties.getAuthRealm())
                .clientId(properties.getClientId());

        if (properties.getClientSecret() != null && !properties.getClientSecret().isBlank()) {
            builder.clientSecret(properties.getClientSecret());
        } else {
            builder.username(properties.getUsername())
                    .password(properties.getPassword());
        }

        return builder.build();
    }
}

