package com.gamehub.chatservice.service;

import com.gamehub.chatservice.service.dto.NotificationMessagePayload;

/**
 * 全局通知推送服务。
 * 封装 WS 推送，便于其他服务/模块调用。
 */
public interface NotificationPushService {

    /**
     * 向指定用户推送一条通知消息（通过 WebSocket）。
     *
     * @param userId 目标用户ID（Keycloak userId / sub）
     * @param payload 通知内容载体
     */
    void sendToUser(String userId, NotificationMessagePayload payload);
}


