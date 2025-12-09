package com.gamehub.chatservice.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 占位：订阅房间事件（创建/删除/成员变更），后续补充业务逻辑。
 */
@Component
@Slf4j
public class RoomEventConsumer {

    @KafkaListener(topics = "${chat.room-events-topic:room-events}", groupId = "chat-service-room-events")
    public void onRoomEvent(String payload) {
        log.debug("Received room event: {}", payload);
        // TODO: 解析并处理房间事件（创建/删除/成员变更）
    }
}

