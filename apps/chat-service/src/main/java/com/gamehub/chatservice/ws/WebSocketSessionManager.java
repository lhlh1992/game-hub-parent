package com.gamehub.chatservice.ws;

import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.WebSocketSessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;

/**
 * 记录/清理 WS 会话，支持“后连踢前”（参考 game-service）。
 */
@Component
@Slf4j
public class WebSocketSessionManager {

    private final SessionRegistry sessionRegistry;
    private final WebSocketDisconnectHelper disconnectHelper;

    public WebSocketSessionManager(SessionRegistry sessionRegistry,
                                   WebSocketDisconnectHelper disconnectHelper) {
        this.sessionRegistry = sessionRegistry;
        this.disconnectHelper = disconnectHelper;
    }

    @EventListener
    public void handleSessionConnect(SessionConnectEvent event) {
        String sessionId = event.getMessage().getHeaders().get("simpSessionId", String.class);
        Principal principal = (Principal) event.getMessage().getHeaders().get("simpUser");
        if (!StringUtils.hasText(sessionId) || principal == null) {
            log.warn("WS connect missing session or principal");
            return;
        }
        String loginSessionId = extractLoginSessionId(principal);
        WebSocketSessionInfo info = WebSocketSessionInfo.builder()
                .sessionId(sessionId)
                .userId(principal.getName())
                .loginSessionId(loginSessionId)
                .service("chat-service")
                .build();
        List<WebSocketSessionInfo> kicked = sessionRegistry.registerWebSocketSessionEnforceSingle(info, 0);
        log.info("WS connected: service=chat-service, user={}, loginSessionId={}, session={}, kicked={}",
                principal.getName(), loginSessionId, sessionId, kicked.size());

        // 与 game-service 保持一致：如果存在旧连接，逐一发送踢人通知并强制断开
        if (kicked != null && !kicked.isEmpty()) {
            kicked.forEach(old -> {
                try {
                    disconnectHelper.sendKickMessage(principal.getName(), old.getSessionId(), "账号已在其他终端登录");
                    disconnectHelper.forceDisconnect(old.getSessionId());
                } catch (Exception e) {
                    log.warn("force disconnect old chat ws failed: user={}, oldSession={}", principal.getName(), old.getSessionId(), e);
                }
            });
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        sessionRegistry.unregisterWebSocketSession(sessionId);
        log.info("WS disconnected: session={}", sessionId);
    }

    private String extractLoginSessionId(Principal principal) {
        if (principal instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            Object sid = jwt.getClaim("sid");
            if (sid != null && StringUtils.hasText(sid.toString())) {
                return sid.toString();
            }
            Object sessionState = jwt.getClaim("session_state");
            if (sessionState != null && StringUtils.hasText(sessionState.toString())) {
                return sessionState.toString();
            }
        }
        return null;
    }
}

