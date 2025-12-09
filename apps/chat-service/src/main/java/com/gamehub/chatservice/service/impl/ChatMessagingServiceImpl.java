package com.gamehub.chatservice.service.impl;

import com.gamehub.chatservice.service.ChatMessagingService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ChatMessagingServiceImpl implements ChatMessagingService {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void sendLobbyMessage(String userId, String content) {
        Assert.hasText(userId, "userId required");
        String body = sanitize(content);
        if (body == null) {
            return;
        }
        BroadcastPayload payload = new BroadcastPayload("LOBBY", null, userId, body, Instant.now().toEpochMilli());
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
        BroadcastPayload payload = new BroadcastPayload("ROOM", roomId, userId, body, Instant.now().toEpochMilli());
        messagingTemplate.convertAndSend("/topic/chat.room." + roomId, payload);
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

    private record BroadcastPayload(
            String type,
            String roomId,
            String senderId,
            String content,
            Long timestamp
    ) {
    }
}

