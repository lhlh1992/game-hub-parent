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
 * WebSocket 聊天消息控制器
 * 处理大厅、房间、私聊等各类消息的发送
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatWsController {

    private final ChatMessagingService chatMessagingService;

    /**
     * 发送大厅消息
     * 消息会广播给所有订阅 /topic/chat.lobby 的用户
     *
     * @param cmd 消息命令（包含 content）
     * @param sha  STOMP 消息头访问器，用于获取当前用户信息
     */
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

    /**
     * 发送房间消息
     * 消息会广播给所有订阅 /topic/chat.room.{roomId} 的用户
     *
     * @param cmd 消息命令（包含 roomId 和 content）
     * @param sha  STOMP 消息头访问器，用于获取当前用户信息
     */
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

    /**
     * 发送私聊消息
     * 消息会通过点对点方式发送给目标用户（/user/{targetUserId}/queue/chat.private）
     * 只有好友之间才能发送私聊消息
     *
     * @param cmd 消息命令（包含 targetUserId 和 content）
     * @param sha  STOMP 消息头访问器，用于获取当前用户信息
     */
    @MessageMapping("/chat.private.send")
    public void sendPrivate(@Payload SendPrivateMessage cmd, SimpMessageHeaderAccessor sha) {
        if (sha.getUser() == null) {
            log.warn("WS private send denied: missing principal");
            return;
        }
        String senderId = sha.getUser().getName();
        
        if (!StringUtils.hasText(cmd.getTargetUserId()) || !StringUtils.hasText(cmd.getContent())) {
            log.warn("WS private send denied: missing targetUserId or content, senderId={}", senderId);
            return;
        }
        
        boolean success = chatMessagingService.sendPrivateMessage(
                senderId,
                cmd.getTargetUserId(),
                cmd.getContent(),
                cmd.getClientOpId()
        );
        
        if (!success) {
            log.debug("私聊消息发送失败: senderId={}, targetUserId={}", senderId, cmd.getTargetUserId());
        }
    }

    /**
     * 通用消息发送命令（用于大厅和房间）
     */
    @Data
    public static class SendMessage {
        private String roomId;      // 房间ID（大厅消息为 null）
        private String content;    // 消息内容
        private String clientOpId;  // 预留幂等键（客户端操作ID，用于去重）
    }

    /**
     * 私聊消息发送命令
     */
    @Data
    public static class SendPrivateMessage {
        private String targetUserId; // 接收者用户ID（Keycloak用户ID，String格式）
        private String content;      // 消息内容
        private String clientOpId;   // 预留幂等键（客户端操作ID，用于去重）
    }
}