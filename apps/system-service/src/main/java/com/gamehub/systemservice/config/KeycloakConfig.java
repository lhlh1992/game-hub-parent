package com.gamehub.systemservice.config;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keycloak Admin Client 配置
 * 用于通过 Admin API 创建和管理 Keycloak 用户
 */
@Configuration
public class KeycloakConfig {

    @Value("${keycloak.server-url:http://127.0.0.1:8180}")
    private String serverUrl;

    @Value("${keycloak.realm:my-realm}")
    private String realm;

    @Value("${keycloak.client-id:admin-cli}")
    private String clientId;

    @Value("${keycloak.client-secret:}")
    private String clientSecret;

    @Value("${keycloak.admin-username:admin}")
    private String adminUsername;

    @Value("${keycloak.admin-password:admin}")
    private String adminPassword;

    /**
     * 创建 Keycloak Admin Client
     * 使用用户名密码方式认证（适用于开发环境）
     */
    @Bean
    public Keycloak keycloakAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")  // 使用 master realm 进行管理员认证
                .clientId("admin-cli")  // admin-cli 是 Keycloak 默认的管理客户端
                .username(adminUsername)
                .password(adminPassword)
                .build();
    }
}

