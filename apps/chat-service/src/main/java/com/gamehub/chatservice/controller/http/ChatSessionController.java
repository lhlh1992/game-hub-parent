package com.gamehub.chatservice.controller.http;

import com.gamehub.chatservice.entity.ChatMessage;
import com.gamehub.chatservice.entity.ChatSession;
import com.gamehub.chatservice.service.ChatSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天会话 HTTP 接口
 * 提供会话列表查询、消息查询、未读消息计数等功能
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    /**
     * 查询当前用户的所有会话列表（包含未读数）
     *
     * @param jwt JWT Token，用于获取当前用户ID
     * @return 会话列表
     */
    @GetMapping
    public ResponseEntity<List<SessionResponse>> listSessions(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getSubject())) {
            return ResponseEntity.status(401).build();
        }

        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            List<ChatSessionService.SessionInfo> sessions = chatSessionService.listUserSessions(userId);

            List<SessionResponse> response = sessions.stream()
                    .map(info -> {
                        SessionResponse resp = new SessionResponse();
                        resp.setSessionId(info.session().getId().toString());
                        resp.setSessionType(info.session().getSessionType().name());
                        resp.setSessionName(info.session().getSessionName());
                        resp.setLastMessageTime(info.session().getLastMessageTime());
                        resp.setUnreadCount(info.unreadCount());
                        
                        // 设置最后一条消息
                        if (info.lastMessage() != null) {
                            resp.setLastMessage(info.lastMessage().getContent());
                            resp.setLastMessageTime(info.lastMessage().getCreatedAt());
                        }
                        
                        // 对于私聊会话，设置对方用户ID（用于前端匹配）
                        if (info.otherUserId() != null) {
                            resp.setOtherUserId(info.otherUserId().toString());
                        }
                        
                        return resp;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询会话列表失败: userId={}", jwt.getSubject(), e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 查询会话的消息列表
     *
     * @param sessionId 会话ID
     * @param limit     最大条数（默认100）
     * @param jwt       JWT Token，用于验证权限
     * @return 消息列表
     */
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<MessageResponse>> listMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getSubject())) {
            return ResponseEntity.status(401).build();
        }

        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            List<ChatMessage> messages = chatSessionService.listMessages(sessionUuid, limit);

            List<MessageResponse> response = messages.stream()
                    .map(msg -> {
                        MessageResponse resp = new MessageResponse();
                        resp.setMessageId(msg.getId().toString());
                        resp.setSessionId(msg.getSessionId().toString());
                        resp.setSenderId(msg.getSenderId().toString());
                        resp.setMessageType(msg.getMessageType().name());
                        resp.setContent(msg.getContent());
                        resp.setCreatedAt(msg.getCreatedAt());
                        resp.setIsRecalled(msg.getIsRecalled());
                        return resp;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询消息列表失败: sessionId={}", sessionId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 标记会话消息为已读
     *
     * @param sessionId 会话ID
     * @param messageId 已读到的消息ID（可选，如果不提供则标记为最后一条消息）
     * @param jwt       JWT Token，用于获取当前用户ID
     * @return 成功响应
     */
    @PostMapping("/{sessionId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "messageId", required = false) String messageId,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getSubject())) {
            return ResponseEntity.status(401).build();
        }

        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            UUID userId = UUID.fromString(jwt.getSubject());
            UUID messageUuid = messageId != null ? UUID.fromString(messageId) : null;

            chatSessionService.markAsRead(sessionUuid, userId, messageUuid);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("标记消息已读失败: sessionId={}, userId={}", sessionId, jwt.getSubject(), e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 通过两个用户ID获取或创建私聊会话ID
     * 用于前端获取 sessionId 以便标记已读
     *
     * @param otherUserId 对方用户ID（Keycloak用户ID，UUID格式）
     * @param jwt          JWT Token，用于获取当前用户ID
     * @return 会话ID
     */
    @GetMapping("/private/{otherUserId}")
    public ResponseEntity<SessionIdResponse> getOrCreatePrivateSessionId(
            @PathVariable("otherUserId") String otherUserId,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getSubject())) {
            return ResponseEntity.status(401).build();
        }

        try {
            UUID currentUserId = UUID.fromString(jwt.getSubject());
            UUID otherUserUuid = UUID.fromString(otherUserId);

            UUID sessionId = chatSessionService.getOrCreatePrivateSessionId(currentUserId, otherUserUuid);

            SessionIdResponse response = new SessionIdResponse();
            response.setSessionId(sessionId.toString());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取或创建私聊会话失败: currentUserId={}, otherUserId={}", jwt.getSubject(), otherUserId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 查询会话的未读消息数
     *
     * @param sessionId 会话ID
     * @param jwt      JWT Token，用于获取当前用户ID
     * @return 未读消息数
     */
    @GetMapping("/{sessionId}/unread")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(
            @PathVariable("sessionId") String sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getSubject())) {
            return ResponseEntity.status(401).build();
        }

        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            UUID userId = UUID.fromString(jwt.getSubject());

            long unreadCount = chatSessionService.countUnreadMessages(sessionUuid, userId);

            UnreadCountResponse response = new UnreadCountResponse();
            response.setSessionId(sessionId);
            response.setUnreadCount(unreadCount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询未读消息数失败: sessionId={}, userId={}", sessionId, jwt.getSubject(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @Data
    public static class SessionResponse {
        private String sessionId;
        private String sessionType;
        private String sessionName;
        private String lastMessage;
        private java.time.OffsetDateTime lastMessageTime;
        private long unreadCount;
        private String otherUserId; // 对方用户ID（仅私聊会话使用）
    }

    @Data
    public static class MessageResponse {
        private String messageId;
        private String sessionId;
        private String senderId;
        private String messageType;
        private String content;
        private java.time.OffsetDateTime createdAt;
        private Boolean isRecalled;
    }

    @Data
    public static class UnreadCountResponse {
        private String sessionId;
        private long unreadCount;
    }

    @Data
    public static class SessionIdResponse {
        private String sessionId;
    }
}
