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
 * 
 * 重要：支持基于 loginSessionId 的事件通知，实现精确的会话管理。
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
     * 登录会话 ID（loginSessionId）。
     * 
     * 可选字段：如果提供，可以基于 loginSessionId 精确查询和断开会话。
     * 如果未提供，将基于 userId 查询所有会话（向后兼容）。
     * 
     * 从 JWT claim 中的 sid 提取。
     */
    private String loginSessionId;
    
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
        return new SessionInvalidatedEvent(userId, null, eventType, Instant.now().toEpochMilli(), reason);
    }
    
    /**
     * 创建事件实例（无原因）
     */
    public static SessionInvalidatedEvent of(String userId, EventType eventType) {
        return of(userId, eventType, null);
    }
    
    /**
     * 创建事件实例（包含 loginSessionId）
     */
    public static SessionInvalidatedEvent of(String userId, String loginSessionId, EventType eventType, String reason) {
        return new SessionInvalidatedEvent(userId, loginSessionId, eventType, Instant.now().toEpochMilli(), reason);
    }
    
    /**
     * 创建事件实例（包含 loginSessionId，无原因）
     */
    public static SessionInvalidatedEvent of(String userId, String loginSessionId, EventType eventType) {
        return of(userId, loginSessionId, eventType, null);
    }
}

