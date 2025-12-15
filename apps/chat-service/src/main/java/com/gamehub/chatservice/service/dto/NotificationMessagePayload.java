package com.gamehub.chatservice.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 推送给前端的通知消息载体。
 */
@Data
@Builder
public class NotificationMessagePayload {
    /**
     * 通知唯一ID（推荐传 UUID 字符串，便于前端去重和标记已读）
     */
    private String id;

    /**
     * 通知类型，如：INFO、FRIEND_REQUEST、SYSTEM_ALERT 等。
     */
    private String type;

    /**
     * 通知标题。
     */
    private String title;

    /**
     * 通知内容/文案。
     */
    private String content;

    /**
     * 触发方用户（可选）。
     */
    private String fromUserId;

    /**
     * 额外透传数据，前端可用作跳转/操作。
     * 使用 Object 以兼容 Map/List/简单类型。
     */
    private Object payload;

    /**
     * 操作列表（可选）：如 ["ACCEPT", "REJECT"]，提示前端此通知可操作。
     */
    private String[] actions;

    /**
     * 生成时间戳（毫秒）。
     */
    @Builder.Default
    private Long timestamp = Instant.now().toEpochMilli();
}


