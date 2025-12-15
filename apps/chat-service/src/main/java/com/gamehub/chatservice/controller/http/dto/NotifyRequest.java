package com.gamehub.chatservice.controller.http.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 内部通知推送请求体。
 */
@Data
public class NotifyRequest {
    /**
     * 目标用户ID（Keycloak userId / sub）。
     */
    @NotBlank(message = "userId is required")
    private String userId;

    /**
     * 通知类型（INFO、FRIEND_REQUEST...）。
     */
    @NotBlank(message = "type is required")
    private String type;

    /**
     * 通知标题。
     */
    @NotBlank(message = "title is required")
    private String title;

    /**
     * 通知内容。
     */
    @NotBlank(message = "content is required")
    private String content;

    /**
     * 触发方用户（可选）。
     */
    private String fromUserId;

    /**
     * 额外透传数据（可选）。
     */
    private Object payload;

    /**
     * 通知唯一ID（可选，建议传递），用于前端去重、标记已读。
     * 建议传 sys_notification.id 或业务侧生成的 UUID。
     */
    private String notificationId;

    /**
     * 可操作类型（可选），如 ["ACCEPT","REJECT"]。
     */
    private String[] actions;
}



