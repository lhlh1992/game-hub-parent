package com.gamehub.chatservice.service.impl;

import com.gamehub.chatservice.service.ChatHistoryService;
import com.gamehub.chatservice.service.ChatMessagingService;
import com.gamehub.chatservice.service.UserProfileCacheService;
import com.gamehub.chatservice.service.dto.ChatMessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessagingServiceImpl implements ChatMessagingService {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserProfileCacheService userProfileCacheService;
    private final ChatHistoryService chatHistoryService;

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

