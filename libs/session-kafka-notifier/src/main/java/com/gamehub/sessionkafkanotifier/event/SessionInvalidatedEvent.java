package com.gamehub.sessionkafkanotifier.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 会话失效事件。
 * 
 * 当用户登出、修改密码、被禁用等操作时，通过 Kafka 广播此事件，
 * 各服务监听到后执行本地会话清理（如断开 WebSocket 连接）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionInvalidatedEvent {
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 事件类型
     */
    private EventType eventType;
    
    /**
     * 事件触发时间（时间戳）
     */
    private Long timestamp;
    
    /**
     * 可选：触发原因描述
     */
    private String reason;
    
    /**
     * 事件类型枚举
     */
    public enum EventType {
        /** 用户登出 */
        LOGOUT,
        /** 修改密码 */
        PASSWORD_CHANGED,
        /** 用户被禁用 */
        USER_DISABLED,
        /** 管理员强制下线 */
        FORCE_LOGOUT,
        /** 其他原因 */
        OTHER
    }
    
    /**
     * 创建事件实例（自动设置时间戳）
     */
    public static SessionInvalidatedEvent of(String userId, EventType eventType, String reason) {
        return new SessionInvalidatedEvent(userId, eventType, Instant.now().toEpochMilli(), reason);
    }
    
    /**
     * 创建事件实例（无原因）
     */
    public static SessionInvalidatedEvent of(String userId, EventType eventType) {
        return of(userId, eventType, null);
    }
}

