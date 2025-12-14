package com.gamehub.systemservice.infrastructure.client.chat.dto;

import lombok.Data;

/**
 * chat-service 通知推送请求体。
 */
@Data
public class NotifyPushRequest {
    private String userId;          // 目标用户（Keycloak userId/sub）
    private String type;            // 通知类型：INFO、FRIEND_REQUEST 等
    private String title;           // 标题
    private String content;         // 内容
    private String fromUserId;      // 触发方（可选）
    private Object payload;         // 透传数据
    private String[] actions;       // 可操作按钮（可选）
}


