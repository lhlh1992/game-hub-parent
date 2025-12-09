package com.gamehub.chatservice.controller.ws;

import com.gamehub.chatservice.service.ChatMessagingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

/**
 * WebSocket 聊天入口（最小可用）：大厅/房间消息发送。
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatWsController {

    private final ChatMessagingService chatMessagingService;

    @MessageMapping("/chat.lobby.send")
    public void sendLobby(@Payload SendMessage cmd, SimpMessageHeaderAccessor sha) {
        if (sha.getUser() == null) {
            log.warn("WS lobby send denied: missing principal");
            return;
        }
        if (!StringUtils.hasText(cmd.getContent())) {
            return;
        }
        chatMessagingService.sendLobbyMessage(sha.getUser().getName(), cmd.getContent());
    }

    @MessageMapping("/chat.room.send")
    public void sendRoom(@Payload SendMessage cmd, SimpMessageHeaderAccessor sha) {
        if (sha.getUser() == null) {
            log.warn("WS room send denied: missing principal");
            return;
        }
        if (!StringUtils.hasText(cmd.getRoomId()) || !StringUtils.hasText(cmd.getContent())) {
            return;
        }
        chatMessagingService.sendRoomMessage(sha.getUser().getName(), cmd.getRoomId(), cmd.getContent());
    }

    @Data
    public static class SendMessage {
        private String roomId;
        private String content;
        private String clientOpId; // 预留幂等键
    }
}