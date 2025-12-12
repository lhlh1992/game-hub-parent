package com.gamehub.systemservice.service.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class NotificationView {
    /**
     * 通知唯一 ID（与 sys_notification.id 对应）
     */
    private UUID id;
    /**
     * 通知类型，如 FRIEND_REQUEST、SYSTEM_ALERT 等
     */
    private String type;
    /**
     * 通知标题
     */
    private String title;
    /**
     * 通知正文
     */
    private String content;
    /**
     * 状态：UNREAD / READ / ARCHIVED / DELETED
     */
    private String status;
    /**
     * 触发方 Keycloak userId（可选）
     */
    private String fromUserId;
    /**
     * 关联业务类型/ID（用于跳转、去重）
     */
    private String refType;
    private UUID refId;
    /**
     * 透传数据（jsonb）
     */
    private Map<String, Object> payload;
    /**
     * 可用操作按钮，如 ["ACCEPT","REJECT"]
     */
    private List<String> actions;
    /**
     * 来源服务标识（如 system-service）
     */
    private String sourceService;
    /**
     * 创建时间（服务端生成）
     */
    private OffsetDateTime createdAt;
    /**
     * 已读时间（未读为 null）
     */
    private OffsetDateTime readAt;
}

