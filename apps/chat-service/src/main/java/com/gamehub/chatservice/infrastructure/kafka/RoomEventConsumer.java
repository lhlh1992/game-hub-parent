package com.gamehub.chatservice.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.chatservice.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 订阅房间事件（创建/删除），用于清理房间聊天记录。（暂时没用）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RoomEventConsumer {

    private final ChatHistoryService chatHistoryService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${chat.room-events-topic:room-events}", groupId = "chat-service-room-events")
    public void onRoomEvent(String payload) {
        log.debug("Received room event: {}", payload);
        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("eventType").asText();
            String roomId = node.path("roomId").asText();
            if (!StringUtils.hasText(type) || !StringUtils.hasText(roomId)) {
                return;
            }
            String normalized = type.toUpperCase();
            if (normalized.contains("DESTROY") || normalized.contains("DELETE") || normalized.contains("CLOSE")) {
                chatHistoryService.deleteRoomMessages(roomId);
                log.info("Room event: {} -> cleared chat history for roomId={}", type, roomId);
            }
        } catch (Exception e) {
            log.warn("Failed to handle room event payload={}, err={}", payload, e.getMessage());
        }
    }
}
