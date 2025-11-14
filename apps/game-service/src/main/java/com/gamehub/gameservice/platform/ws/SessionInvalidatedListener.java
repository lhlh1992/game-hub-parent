package com.gamehub.gameservice.platform.ws;

import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.WebSocketSessionInfo;
import com.gamehub.sessionkafkanotifier.event.SessionInvalidatedEvent;
import com.gamehub.sessionkafkanotifier.listener.SessionEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 会话失效事件监听器。
 * 
 * 监听来自 Kafka 的会话失效事件（如用户登出、修改密码等），
 * 自动断开该用户在 game-service 中的所有 WebSocket 连接。
 */
@Slf4j
@Component
public class SessionInvalidatedListener implements SessionEventListener {

    /** 会话注册表，用于查询用户的 WebSocket 会话 */
    private final SessionRegistry sessionRegistry;

    /** WebSocket 断连工具类，提供统一的断连方法 */
    private final WebSocketDisconnectHelper disconnectHelper;

    public SessionInvalidatedListener(
            SessionRegistry sessionRegistry,
            WebSocketDisconnectHelper disconnectHelper) {
        this.sessionRegistry = sessionRegistry;
        this.disconnectHelper = disconnectHelper;
    }

    /**
     * 处理会话失效事件。
     * 
     * 当收到会话失效事件时（如用户登出），查询该用户在 game-service 中的所有 WebSocket 会话，
     * 发送踢人通知并强制断开连接。
     * 
     * @param event 会话失效事件
     */
    @Override
    public void onSessionInvalidated(SessionInvalidatedEvent event) {
        String userId = event.getUserId();
        log.info("收到会话失效事件，开始断开用户 WebSocket 连接: userId={}, eventType={}, reason={}", 
                userId, event.getEventType(), event.getReason());

        // 查询该用户在 game-service 中的所有 WebSocket 会话
        List<WebSocketSessionInfo> wsSessions = sessionRegistry.getWebSocketSessions(userId);
        
        // 过滤出 game-service 的会话
        List<WebSocketSessionInfo> gameServiceSessions = wsSessions.stream()
                .filter(session -> "game-service".equals(session.getService()))
                .toList();

        if (CollectionUtils.isEmpty(gameServiceSessions)) {
            log.debug("用户 {} 在 game-service 中无 WebSocket 连接", userId);
            return;
        }

        log.info("用户 {} 在 game-service 中有 {} 个 WebSocket 连接，开始断开", userId, gameServiceSessions.size());

        // 生成踢人原因
        String reason = getKickReason(event);
        
        // 对每个会话执行断连操作
        for (WebSocketSessionInfo session : gameServiceSessions) {
            try {
                // 1. 发送踢人通知
                disconnectHelper.sendKickMessage(userId, session.getSessionId(), reason);
                
                // 2. 强制断开连接
                disconnectHelper.forceDisconnect(session.getSessionId());
                
                // 3. 从会话注册表中移除（虽然连接已断开，但清理 Redis 中的记录）
                sessionRegistry.unregisterWebSocketSession(session.getSessionId());
                
                log.debug("已断开用户 {} 的 WebSocket 连接: sessionId={}", userId, session.getSessionId());
            } catch (Exception e) {
                log.error("断开用户 {} WebSocket 连接失败: sessionId={}", userId, session.getSessionId(), e);
            }
        }

        log.info("用户 {} 的所有 WebSocket 连接已断开: 共 {} 个", userId, gameServiceSessions.size());
    }

    /**
     * 根据事件类型生成踢人原因。
     * 
     * @param event 会话失效事件
     * @return 踢人原因描述
     */
    private String getKickReason(SessionInvalidatedEvent event) {
        if (event.getReason() != null && !event.getReason().isEmpty()) {
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

