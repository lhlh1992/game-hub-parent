package com.gamehub.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录会话信息（基于 JWT/Token 的 HTTP 会话）。
 *
 * 用于记录某个用户的一次登录态，用来统计“在线用户”、执行“强制下线”等操作。
 * 
 * 重要：支持单点登录（后连踢前）功能，通过 loginSessionId 和 status 字段实现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginSessionInfo {

    /**
     * 会话唯一标识（推荐使用 JWT 的 jti，或服务端生成的 UUID）。
     * 
     * 注意：此字段用于向后兼容，新方案中主要使用 loginSessionId。
     */
    private String sessionId;

    /**
     * 登录会话 ID（loginSessionId）。
     * 
     * 这是整个登录生命周期内稳定不变的标识符，用于实现单点登录功能。
     * 推荐使用 Keycloak 的 sid（Session ID），从 JWT claim 中提取。
     * 
     * 特性：
     * - 在一次登录内稳定不变
     * - token 刷新、重发，都挂在同一个 loginSessionId 下
     * - 新登录会产生新的 loginSessionId，不与旧登录共享
     */
    private String loginSessionId;

    /**
     * 会话状态。
     * 
     * 用于标识会话的当前状态：
     * - ACTIVE：当前有效
     * - KICKED：被后续登录踢下线
     * - EXPIRED：正常超时或注销
     * 
     * 默认值：ACTIVE（向后兼容，旧数据默认为 ACTIVE）
     */
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * 原始 Token 值（生产环境建议存储哈希值而不是明文）。
     */
    private String token;

    /**
     * 关联的用户 ID。
     */
    private String userId;

    /**
     * 签发时间（毫秒时间戳）。
     */
    private Long issuedAt;

    /**
     * 过期时间（毫秒时间戳）。
     */
    private Long expiresAt;

    /**
     * 额外信息（如 IP、User-Agent、设备名等）。
     */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();

    /**
     * 是否已过期。
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt > 0 && expiresAt < Instant.now().toEpochMilli();
    }
}
