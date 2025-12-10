package com.gamehub.chatservice.ws;

import com.gamehub.session.SessionRegistry;
import com.gamehub.session.event.SessionInvalidatedEvent;
import com.gamehub.session.model.WebSocketSessionInfo;
import com.gamehub.sessionkafkanotifier.listener.SessionEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 会话失效事件监听器（登出/改密/禁用等），参考 game-service。
 * 收到 session-invalidated 事件后，断开该用户在 chat-service 的所有 WebSocket 连接。
 */
@Slf4j
@Component
public class SessionInvalidatedListener implements SessionEventListener {

    private final SessionRegistry sessionRegistry;
    private final WebSocketDisconnectHelper disconnectHelper;

    public SessionInvalidatedListener(
            SessionRegistry sessionRegistry,
            WebSocketDisconnectHelper disconnectHelper) {
        this.sessionRegistry = sessionRegistry;
        this.disconnectHelper = disconnectHelper;
    }

    @Override
    public void onSessionInvalidated(SessionInvalidatedEvent event) {
        String userId = event.getUserId();
        String loginSessionId = event.getLoginSessionId();
        log.info("【chat-service】收到会话失效事件，准备断开 WebSocket: userId={}, loginSessionId={}, eventType={}, reason={}",
                userId, loginSessionId, event.getEventType(), event.getReason());

        List<WebSocketSessionInfo> chatSessions;
        if (loginSessionId != null && !loginSessionId.isBlank()) {
            // 基于 loginSessionId 精确查询
            List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessionsByLoginSessionId(loginSessionId);
            chatSessions = wsSessions.stream()
                    .filter(session -> "chat-service".equals(session.getService()))
                    .toList();
        } else {
            // 兼容仅有 userId 的场景
            List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessions(userId);
            chatSessions = wsSessions.stream()
                    .filter(session -> "chat-service".equals(session.getService()))
                    .toList();
        }

        if (CollectionUtils.isEmpty(chatSessions)) {
            log.debug("【chat-service】用户 {} 无 WebSocket 连接", userId);
            return;
        }

        String reason = resolveReason(event);
        log.info("【chat-service】开始断开用户 {} 的 WebSocket 连接，共 {} 个", userId, chatSessions.size());

        for (WebSocketSessionInfo session : chatSessions) {
            try {
                disconnectHelper.sendKickMessage(userId, session.getSessionId(), reason);
                disconnectHelper.forceDisconnect(session.getSessionId());
                sessionRegistry.unregisterWebSocketSession(session.getSessionId());
            } catch (Exception e) {
                log.error("【chat-service】断开 WebSocket 失败: userId={}, sessionId={}", userId, session.getSessionId(), e);
            }
        }
    }

    private String resolveReason(SessionInvalidatedEvent event) {
        if (event.getReason() != null && !event.getReason().isBlank()) {
            return event.getReason();
        }
        return switch (event.getEventType()) {
            case LOGOUT -> "用户已登出";
            case PASSWORD_CHANGED -> "密码已修改，请重新登录";
            case USER_DISABLED -> "账号已被禁用";
            case FORCE_LOGOUT -> "管理员强制下线";
            case OTHER -> "会话已失效";
        };
    }
}


