package com.gamehub.session.model;

/**
 * 会话状态枚举。
 * 
 * 用于标识登录会话的当前状态，支持单点登录（后连踢前）功能。
 */
public enum SessionStatus {
    
    /**
     * 当前有效。
     * 会话处于活跃状态，用户可以正常使用。
     */
    ACTIVE,
    
    /**
     * 被后续登录踢下线。
     * 用户在其他设备/浏览器登录后，此会话被标记为 KICKED。
     * 所有使用此会话的请求都应该被拒绝。
     */
    KICKED,
    
    /**
     * 正常超时或注销。
     * 会话已过期或被用户主动注销。
     */
    EXPIRED
}

