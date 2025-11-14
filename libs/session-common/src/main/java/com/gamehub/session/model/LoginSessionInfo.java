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
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginSessionInfo {

    /**
     * 会话唯一标识（推荐使用 JWT 的 jti，或服务端生成的 UUID）。
     */
    private String sessionId;

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
