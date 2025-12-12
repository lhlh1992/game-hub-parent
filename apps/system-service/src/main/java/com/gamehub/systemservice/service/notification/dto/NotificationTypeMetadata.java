package com.gamehub.systemservice.service.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 通知类型元数据：告知前端该类型是否可操作、支持哪些动作。
 * 用于前端渲染提示（实际行为仍由前端内置 handler 决定）。
 */
@Data
@Builder
public class NotificationTypeMetadata {
    /**
     * 通知类型标识（如 FRIEND_REQUEST、FRIEND_RESULT、SYSTEM_ALERT）
     */
    private String type;
    /**
     * 是否可操作（需要渲染按钮）
     */
    private boolean actionable;
    /**
     * 推荐动作列表（如 ["ACCEPT","REJECT"]）
     */
    private List<String> actions;
    /**
     * 简要描述
     */
    private String description;
}

