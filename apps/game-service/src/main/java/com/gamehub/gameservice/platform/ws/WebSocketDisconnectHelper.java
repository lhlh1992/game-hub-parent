package com.gamehub.gameservice.platform.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket 断连工具类。
 * 
 * 提供统一的 WebSocket 断连方法，供多个组件复用：
 * - {@link WebSocketSessionManager}：单点登录时踢掉旧连接
 * - {@link SessionInvalidatedListener}：会话失效时断开连接
 */
@Slf4j
@Component
public class WebSocketDisconnectHelper {

    /** 踢人消息的目标队列地址 */
    private static final String KICK_DESTINATION = "/queue/system.kick";

    /** STOMP 消息模板，用于向客户端发送踢人通知 */
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /** 客户端入站消息通道，用于强制断开连接 */
    private final MessageChannel clientInboundChannel;

    public WebSocketDisconnectHelper(
            org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate,
            @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel) {
        this.messagingTemplate = messagingTemplate;
        this.clientInboundChannel = clientInboundChannel;
    }

    /**
     * 向客户端发送踢人通知。
     * 
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param reason 踢人原因
     */
    public void sendKickMessage(String userId, String sessionId, String reason) {
        try {
            SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headerAccessor.setSessionId(sessionId);
            headerAccessor.setLeaveMutable(true);
            
            messagingTemplate.convertAndSendToUser(
                    userId,
                    KICK_DESTINATION,
                    Map.of("type", "WS_KICK", "reason", reason),
                    headerAccessor.getMessageHeaders()
            );
        } catch (Exception e) {
            log.warn("发送踢人通知失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /**
     * 强制断开 WebSocket 连接。
     * 
     * @param sessionId 会话 ID
     */
    public void forceDisconnect(String sessionId) {
        try {
            // 创建 STOMP DISCONNECT 命令（断连命令）
            StompHeaderAccessor header = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            header.setSessionId(sessionId);
            header.setLeaveMutable(true);
            // 发送断连命令到客户端入站通道，触发框架断开连接
            clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], header.getMessageHeaders()));
        } catch (Exception e) {
            log.warn("强制断开连接失败: sessionId={}", sessionId, e);
        }
    }
}

