package com.gamehub.systemservice.service.keycloak;

import com.gamehub.systemservice.dto.keycloak.KeycloakEventPayload;

public interface KeycloakEventService {

    /**
     * 处理来自 Keycloak 的事件（用户事件或管理事件）
     */
    void handleEvent(KeycloakEventPayload payload);
}





