package com.gamehub.systemservice.dto.keycloak;

import lombok.Data;

import java.util.Map;

/**
 * 通用的 Keycloak 事件负载（兼容 event 与 adminEvent）
 * 参考 keycloak-event-listener-rest 的默认 JSON 格式
 */
@Data
public class KeycloakEventPayload {
    // 普通用户事件
    private Event event;
    // 管理事件（如 Admin 创建用户）
    private AdminEvent adminEvent;
    // 错误消息（可选）
    private String error;

    @Data
    public static class Event {
        private String id;
        private String type;            // 例如: REGISTER, UPDATE_PROFILE, LOGIN
        private Long time;
        private String realmId;
        private String clientId;
        private String userId;
        private String ipAddress;
        private Map<String, String> details;
    }

    @Data
    public static class AdminEvent {
        private Long time;
        private String realmId;
        private String authDetailsRealmId;
        private String authDetailsClientId;
        private String authDetailsUserId;
        private String authDetailsIpAddress;
        private String resourceType;    // USER, GROUP, CLIENT 等
        private String operationType;   // CREATE, UPDATE, DELETE
        private String resourcePath;    // users/{id}
        private String representation;  // JSON 字符串
        private Map<String, String> errorDetails;
    }
}





