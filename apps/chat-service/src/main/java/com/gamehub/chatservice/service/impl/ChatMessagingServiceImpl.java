package com.gamehub.chatservice.service.impl;

import com.gamehub.chatservice.entity.ChatMessage;
import com.gamehub.chatservice.entity.ChatSession;
import com.gamehub.chatservice.infrastructure.client.SystemUserClient;
import com.gamehub.chatservice.service.ChatHistoryService;
import com.gamehub.chatservice.service.ChatMessagingService;
import com.gamehub.chatservice.service.ChatSessionService;
import com.gamehub.chatservice.service.UserProfileCacheService;
import com.gamehub.chatservice.service.dto.ChatMessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * 聊天消息服务实现类
 * 负责处理大厅、房间、私聊等各类消息的发送和历史存储
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessagingServiceImpl implements ChatMessagingService {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserProfileCacheService userProfileCacheService;
    private final ChatHistoryService chatHistoryService;
    private final SystemUserClient systemUserClient;
    private final ChatSessionService chatSessionService;

    @Override
    public void sendLobbyMessage(String userId, String content) {
        Assert.hasText(userId, "userId required");
        String body = sanitize(content);
        if (body == null) {
            return;
        }
        ChatMessagePayload payload = ChatMessagePayload.builder()
                .type("LOBBY")
                .roomId(null)
                .senderId(userId)
                .senderName(resolveDisplayName(userId))
                .content(body)
                .timestamp(Instant.now().toEpochMilli())
                .build();
        messagingTemplate.convertAndSend("/topic/chat.lobby", payload);
    }

    @Override
    public void sendRoomMessage(String userId, String roomId, String content) {
        Assert.hasText(userId, "userId required");
        Assert.hasText(roomId, "roomId required");
        String body = sanitize(content);
        if (body == null) {
            return;
        }
        ChatMessagePayload payload = ChatMessagePayload.builder()
                .type("ROOM")
                .roomId(roomId)
                .senderId(userId)
                .senderName(resolveDisplayName(userId))
                .content(body)
                .timestamp(Instant.now().toEpochMilli())
                .build();
        // 1) 推送实时消息
        messagingTemplate.convertAndSend("/topic/chat.room." + roomId, payload);
        // 2) 记录房间历史（保持最近 50 条）
        chatHistoryService.appendRoomMessage(payload, 50);
    }

    /**
     * 简单清洗：去空白并限制长度（先期不做敏感词等复杂处理）。
     */
    private String sanitize(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 保护性截断，避免超长消息
        int maxLen = 500;
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    @Override
    public boolean sendPrivateMessage(String senderId, String targetUserId, String content, String clientOpId) {
        Assert.hasText(senderId, "senderId required");
        Assert.hasText(targetUserId, "targetUserId required");
        
        // 1. 参数校验：不能给自己发消息
        if (senderId.equals(targetUserId)) {
            log.warn("私聊消息发送失败：不能给自己发消息, senderId={}", senderId);
            return false;
        }
        
        // 2. 验证好友关系：只有好友之间才能发送私聊消息
        try {
            boolean isFriend = systemUserClient.isFriend(senderId, targetUserId);
            if (!isFriend) {
                log.warn("私聊消息发送失败：不是好友关系, senderId={}, targetUserId={}", senderId, targetUserId);
                return false;
            }
        } catch (Exception e) {
            log.error("验证好友关系失败: senderId={}, targetUserId={}", senderId, targetUserId, e);
            // 如果验证失败，为了可用性，允许发送（但记录警告）
            // 生产环境建议改为 return false，确保安全性
            log.warn("好友关系验证失败，但允许消息发送（降级策略）");
        }
        
        // 3. 内容清洗
        String body = sanitize(content);
        if (body == null) {
            return false;
        }
        
        // 4. 生成客户端操作ID（用于幂等，如果没有提供则生成一个）
        if (clientOpId == null || clientOpId.isBlank()) {
            clientOpId = java.util.UUID.randomUUID().toString();
        }
        
        // 5. 构建消息载体
        ChatMessagePayload payload = ChatMessagePayload.builder()
                .type("PRIVATE")
                .roomId(null) // 私聊消息没有 roomId
                .senderId(senderId)
                .senderName(resolveDisplayName(senderId))
                .targetUserId(targetUserId) // 设置接收者ID
                .content(body)
                .timestamp(Instant.now().toEpochMilli())
                .clientOpId(clientOpId)
                .build();
        
        // 6. 获取或创建私聊会话（数据库持久化）
        UUID senderUuid;
        UUID targetUuid;
        try {
            senderUuid = UUID.fromString(senderId);
            targetUuid = UUID.fromString(targetUserId);
        } catch (IllegalArgumentException e) {
            log.error("无效的用户ID格式: senderId={}, targetUserId={}", senderId, targetUserId);
            return false;
        }

        ChatSession session;
        try {
            session = chatSessionService.getOrCreatePrivateSession(senderUuid, targetUuid);
        } catch (Exception e) {
            log.error("获取或创建私聊会话失败: senderId={}, targetUserId={}", senderId, targetUserId, e);
            return false;
        }

        // 7. 保存消息到数据库（幂等检查）
        ChatMessage savedMessage;
        try {
            savedMessage = chatSessionService.savePrivateMessage(
                    session.getId(),
                    senderUuid,
                    body,
                    clientOpId
            );
        } catch (IllegalStateException e) {
            // 消息已存在（幂等），视为成功
            log.debug("消息已存在（幂等）: senderId={}, targetUserId={}", senderId, targetUserId);
            return true;
        } catch (Exception e) {
            log.error("保存私聊消息到数据库失败: senderId={}, targetUserId={}", senderId, targetUserId, e);
            return false;
        }

        // 8. 使用点对点消息推送（/user/queue/chat.private）
        // Spring WebSocket 会自动将 /user/{userId}/queue/chat.private 路由到目标用户
        // 只有目标用户能收到这条消息，确保私密性
        try {
            // 更新 payload 的 timestamp 为数据库保存的时间
            payload.setTimestamp(savedMessage.getCreatedAt().toInstant().toEpochMilli());
            
            messagingTemplate.convertAndSendToUser(
                    targetUserId,           // 目标用户ID
                    "/queue/chat.private",  // 队列路径
                    payload                 // 消息内容
            );
            log.debug("私聊消息已发送: sessionId={}, messageId={}, senderId={}, targetUserId={}", 
                    session.getId(), savedMessage.getId(), senderId, targetUserId);
            
            // 9. 同时存储到 Redis（用于快速查询，作为缓存层）
            // 注意：数据库是主存储，Redis 是缓存
            chatHistoryService.appendPrivateMessage(payload, 100);
            
            return true;
        } catch (Exception e) {
            log.error("私聊消息推送失败: senderId={}, targetUserId={}", senderId, targetUserId, e);
            // 即使推送失败，消息已保存到数据库，返回 true
            return true;
        }
    }

    /**
     * 解析发送者显示名称
     * 优先使用缓存中的用户信息，缓存未命中时使用 userId 作为兜底
     *
     * @param userId 用户ID（Keycloak用户ID，String格式）
     * @return 显示名称
     */
    private String resolveDisplayName(String userId) {
        try {
            return userProfileCacheService.get(userId)
                    .map(info -> {
                        if (StringUtils.hasText(info.nickname())) return info.nickname();
                        if (StringUtils.hasText(info.username())) return info.username();
                        return info.userId();
                    })
                    // 缓存未命中，不做远程调用，交给前端兜底
                    .orElse(userId);
        } catch (Exception e) {
            log.warn("resolveDisplayName failed for userId={}", userId, e);
            return userId;
        }
    }

}

