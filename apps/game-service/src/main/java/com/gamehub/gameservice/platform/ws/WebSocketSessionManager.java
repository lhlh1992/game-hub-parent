package com.gamehub.gameservice.platform.ws;

import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.WebSocketSessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 监听 STOMP 连接/断开事件，结合 session-common 实现 WS 单点登录与踢旧。
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    /** 踢人消息的目标队列地址，客户端订阅此地址接收被踢下线通知 */
    private static final String KICK_DESTINATION = "/queue/system.kick";

    /** 会话注册表，用于管理 WebSocket 会话（单点登录、踢旧连接） */
    private final SessionRegistry sessionRegistry;
    
    /** STOMP 消息模板，用于向客户端发送消息（如踢人通知） */
    private final SimpMessagingTemplate messagingTemplate;
    
    /** 客户端入站消息通道，用于向 STOMP 框架发送 DISCONNECT 指令强制断开连接 */
    private final MessageChannel clientInboundChannel;

    public WebSocketSessionManager(SessionRegistry sessionRegistry,
                                   SimpMessagingTemplate messagingTemplate,
                                   @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel) {
        this.sessionRegistry = sessionRegistry;
        this.messagingTemplate = messagingTemplate;
        this.clientInboundChannel = clientInboundChannel;
    }

    /**
     * 连接建立后登记会话，并踢下线旧连接。
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
        WebSocketSessionInfo info = WebSocketSessionInfo.builder()
                .sessionId(sessionId)
                .userId(userId)
                .service("game-service")
                .build();

        List<WebSocketSessionInfo> kicked = sessionRegistry.registerWebSocketSessionEnforceSingle(info, 0);
        if (!CollectionUtils.isEmpty(kicked)) {
            log.info("用户 {} WebSocket 单点登录，新连接 {} 踢掉旧连接 {} 个", userId, sessionId, kicked.size());
            kicked.forEach(old -> {
                sendKickMessage(userId, old.getSessionId());
                forceDisconnect(old.getSessionId());
            });
        } else {
            log.debug("用户 {} WebSocket 连接 {} 注册完成，无旧连接", userId, sessionId);
        }
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

    /**
     * 给被踢下线的旧连接发送通知，提示客户端主动断开。
     */
    private void sendKickMessage(String userId, String targetSessionId) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(targetSessionId);
        headerAccessor.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                userId,
                KICK_DESTINATION,
                Map.of("type", "WS_KICK", "reason", "账号已在其他终端登录"),
                headerAccessor.getMessageHeaders()
        );
    }

    /**
     * 向消息通道发送 DISCONNECT 指令，强制关闭旧会话。
     */
    private void forceDisconnect(String sessionId) {
        StompHeaderAccessor header = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        header.setSessionId(sessionId);
        header.setLeaveMutable(true);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], header.getMessageHeaders()));
    }
}
