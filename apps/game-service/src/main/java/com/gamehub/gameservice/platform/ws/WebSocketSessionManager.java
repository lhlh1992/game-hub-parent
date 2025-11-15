package com.gamehub.gameservice.platform.ws;

import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.WebSocketSessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;

/**
 * 监听 STOMP 连接/断开事件，结合 session-common 实现 WS 单点登录与踢旧。
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    /** 会话注册表，用于管理 WebSocket 会话（单点登录、踢旧连接） */
    private final SessionRegistry sessionRegistry;
    
    /** WebSocket 断连工具类，提供统一的断连方法 */
    private final WebSocketDisconnectHelper disconnectHelper;

    public WebSocketSessionManager(SessionRegistry sessionRegistry,
                                   WebSocketDisconnectHelper disconnectHelper) {
        this.sessionRegistry = sessionRegistry;
        this.disconnectHelper = disconnectHelper;
    }

    /**
     * 连接建立后登记会话，并踢下线旧连接。
     * 
     * 重要：从 JWT 中提取 loginSessionId，支持基于 loginSessionId 的会话管理。
     */
    @EventListener
    public void handleSessionConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal == null) {
            log.warn("收到 SessionConnectEvent 但缺少用户信息，session={}", accessor.getSessionId());
            return; // 未认证用户忽略
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            log.warn("SessionConnectEvent 缺少 sessionId，user={}", principal.getName());
            return;
        }
        String userId = principal.getName();
        
        // 从 JWT 中提取 loginSessionId（如果 Principal 是 JwtAuthenticationToken）
        String loginSessionId = extractLoginSessionId(principal);
        
        WebSocketSessionInfo info = WebSocketSessionInfo.builder()
                .sessionId(sessionId)
                .userId(userId)
                .loginSessionId(loginSessionId) // 可能为 null（向后兼容）
                .service("game-service")
                .build();

        List<WebSocketSessionInfo> kicked = sessionRegistry.registerWebSocketSessionEnforceSingle(info, 0);
        if (!CollectionUtils.isEmpty(kicked)) {
            log.info("用户 {} WebSocket 单点登录，新连接 {} 踢掉旧连接 {} 个, loginSessionId={}", 
                    userId, sessionId, kicked.size(), loginSessionId);
            kicked.forEach(old -> {
                disconnectHelper.sendKickMessage(userId, old.getSessionId(), "账号已在其他终端登录");
                disconnectHelper.forceDisconnect(old.getSessionId());
            });
        } else {
            log.debug("用户 {} WebSocket 连接 {} 注册完成，无旧连接, loginSessionId={}", 
                    userId, sessionId, loginSessionId);
        }
    }
    
    /**
     * 从 Principal 中提取 loginSessionId。
     * 
     * 如果 Principal 是 JwtAuthenticationToken，从 JWT claim 中提取 sid（loginSessionId）。
     * 
     * @param principal 用户 Principal
     * @return loginSessionId，如果无法提取则返回 null
     */
    private String extractLoginSessionId(Principal principal) {
        if (principal instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            // 优先使用 sid
            Object sidObj = jwt.getClaim("sid");
            if (sidObj != null) {
                String sid = sidObj.toString();
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
            }
            // 如果没有 sid，尝试使用 session_state（向后兼容）
            Object sessionStateObj = jwt.getClaim("session_state");
            if (sessionStateObj != null) {
                String sessionState = sessionStateObj.toString();
                if (sessionState != null && !sessionState.isBlank()) {
                    return sessionState;
                }
            }
        }
        return null;
    }

    /**
     * 连接断开时清理会话。
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId != null) {
            sessionRegistry.unregisterWebSocketSession(sessionId);
        }
    }

}
