package com.gamehub.chatservice.controller.ws;

import com.gamehub.chatservice.controller.ws.dto.SendMessage;
import com.gamehub.chatservice.controller.ws.dto.SendPrivateMessage;
import com.gamehub.chatservice.service.ChatMessagingService;
import com.gamehub.chatservice.config.WebSocketTokenStore;
import com.gamehub.web.common.feign.JwtTokenHolder;
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
    private final WebSocketTokenStore tokenStore;

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
        
        // 兜底方案：如果 ThreadLocal 中没有 token，从 Redis 中获取并设置
        // 关键修复：优先使用 loginSessionId（sid）获取 token，因为 token 刷新时 sid 不变
        String token = JwtTokenHolder.getToken();
        if (token == null || token.isBlank()) {
            // 优先从 JWT 中提取 loginSessionId（sid）
            String loginSessionId = null;
            if (sha.getUser() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth) {
                org.springframework.security.oauth2.jwt.Jwt jwt = jwtAuth.getToken();
                Object sidObj = jwt.getClaim("sid");
                if (sidObj != null) {
                    loginSessionId = sidObj.toString();
                }
                if (loginSessionId == null || loginSessionId.isBlank()) {
                    Object sessionStateObj = jwt.getClaim("session_state");
                    if (sessionStateObj != null) {
                        loginSessionId = sessionStateObj.toString();
                    }
                }
            }
            
            // 优先使用 loginSessionId 获取 token
            if (loginSessionId != null && !loginSessionId.isBlank()) {
                token = tokenStore.getToken(loginSessionId);
                if (token != null && !token.isBlank()) {
                    JwtTokenHolder.setToken(token);
                }
            }
            
            // 降级：如果使用 loginSessionId 获取失败，尝试使用 WebSocket sessionId
            if ((token == null || token.isBlank())) {
                String wsSessionId = sha.getSessionId();
                if (wsSessionId != null) {
                    token = tokenStore.getToken(wsSessionId);
                    if (token != null && !token.isBlank()) {
                        JwtTokenHolder.setToken(token);
                    }
                }
            }
        }
        
        chatMessagingService.sendPrivateMessage(
                senderId,
                cmd.getTargetUserId(),
                cmd.getContent(),
                cmd.getClientOpId()
        );
    }
}