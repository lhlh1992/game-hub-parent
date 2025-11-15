package com.gamehub.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket/WebFlux 长连接会话信息。
 *
 * 统一记录用户在各服务（如 game-service、chat-service）的 WS 连接，便于后台管理与强制断开。
 * 
 * 重要：支持基于 loginSessionId 的会话管理，实现单点登录功能。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketSessionInfo {

    /**
     * WebSocket 会话 ID（如 STOMP sessionId、SockJS 连接 ID 等）。
     */
    private String sessionId;

    /**
     * 关联的登录会话 ID（loginSessionId）。
     * 
     * 用于关联 WebSocket 连接与登录会话，支持基于 loginSessionId 的查询和断开。
     * 从 JWT claim 中的 sid 提取。
     * 
     * 可选字段：如果未设置，将基于 userId 进行查询（向后兼容）。
     */
    private String loginSessionId;

    /**
     * 关联的用户 ID。
     */
    private String userId;

    /**
     * 产生该连接的服务名（例如：game-service / chat-service）。
     */
    private String service;

    /**
     * 连接建立时间（毫秒时间戳）。
     */
    private Long connectedAt;

    /**
     * 额外信息（如来源域、IP、User-Agent、房间号等）。
     */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();

    /**
     * 以 ISO-8601 字符串返回建立时间，便于展示。
     */
    public String getConnectedAtIso() {
        if (connectedAt == null) {
            return "";
        }
        return Instant.ofEpochMilli(connectedAt).toString();
    }
}
