package com.gamehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Keycloak Admin API 相关配置。
 *
 * 提供注销 Keycloak SSO 会话所需的服务端地址 / realm / 管理员凭证。
 * 支持通过 application.yml 或环境变量覆盖。
 */
@Component
@ConfigurationProperties(prefix = "keycloak.admin")
public class KeycloakAdminProperties {

    /**
     * Keycloak 服务器地址，例如 http://127.0.0.1:8180
     */
    private String serverUrl = "http://127.0.0.1:8180";

    /**
     * 目标 realm，例如 my-realm
     */
    private String realm = "my-realm";

    /**
     * 获取 admin token 的 realm，通常是 master
     */
    private String authRealm = "master";

    /**
     * 用于调用 admin API 的 clientId（默认 admin-cli）
     */
    private String clientId = "admin-cli";

    private String clientSecret;

    /**
     * admin 用户名（使用密码模式）
     */
    private String username = "admin";

    private String password = "admin";

    // getters and setters
    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getAuthRealm() {
        return authRealm;
    }

    public void setAuthRealm(String authRealm) {
        this.authRealm = authRealm;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

