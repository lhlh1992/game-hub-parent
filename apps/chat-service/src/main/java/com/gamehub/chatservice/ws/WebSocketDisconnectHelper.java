package com.gamehub.chatservice.ws;

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
 * <p>
 * 职责：
 * - 向指定用户发送踢下线通知
 * - 向入站通道发送 DISCONNECT 命令，强制关闭指定 session
 */
@Slf4j
@Component
public class WebSocketDisconnectHelper {

    /** 踢人消息发送的目的地 */
    private static final String KICK_DESTINATION = "/queue/system.kick";

    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final MessageChannel clientInboundChannel;

    public WebSocketDisconnectHelper(
            org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate,
            @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel) {
        this.messagingTemplate = messagingTemplate;
        this.clientInboundChannel = clientInboundChannel;
    }

    /**
     * 发送踢人通知到指定用户的指定 session。
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
     */
    public void forceDisconnect(String sessionId) {
        try {
            StompHeaderAccessor header = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            header.setSessionId(sessionId);
            header.setLeaveMutable(true);
            clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], header.getMessageHeaders()));
        } catch (Exception e) {
            log.warn("强制断开连接失败: sessionId={}", sessionId, e);
        }
    }
}


