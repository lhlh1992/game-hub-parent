package com.gamehub.chatservice.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.chatservice.service.ChatHistoryService;
import com.gamehub.chatservice.service.dto.ChatMessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryServiceImpl implements ChatHistoryService {

    /**
     * Redis List Key 前缀：chat:room:history:{roomId}
     */
    private static final String ROOM_KEY_PREFIX = "chat:room:history:";
    /**
     * 默认最多保留的消息条数
     */
    private static final int DEFAULT_MAX_SIZE = 50;
    /**
     * 默认历史记录 TTL：24 小时
     */
    private static final long DEFAULT_TTL_SECONDS = 24 * 60 * 60;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void appendRoomMessage(ChatMessagePayload payload, int maxSize) {
        if (payload == null || !StringUtils.hasText(payload.getRoomId())) {
            return;
        }
        String key = ROOM_KEY_PREFIX + payload.getRoomId();
        try {
            String json = objectMapper.writeValueAsString(payload);
            stringRedisTemplate.opsForList().rightPush(key, json);
            int keep = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
            stringRedisTemplate.opsForList().trim(key, -keep, -1);
            stringRedisTemplate.expire(key, DEFAULT_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("appendRoomMessage failed: roomId={}, err={}", payload.getRoomId(), e.getMessage());
        }
    }

    @Override
    public List<ChatMessagePayload> listRoomMessages(String roomId, int limit) {
        Assert.hasText(roomId, "roomId required");
        String key = ROOM_KEY_PREFIX + roomId;
        int fetch = limit > 0 ? limit : DEFAULT_MAX_SIZE;
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return Collections.emptyList();
        }
        long start = Math.max(-fetch, -size);
        List<String> raw = stringRedisTemplate.opsForList().range(key, start, -1);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatMessagePayload> result = new ArrayList<>(raw.size());
        for (String item : raw) {
            try {
                result.add(objectMapper.readValue(item, new TypeReference<>() {}));
            } catch (Exception ignore) {
                // ignore malformed
            }
        }
        return result;
    }

    @Override
    public void deleteRoomMessages(String roomId) {
        if (!StringUtils.hasText(roomId)) {
            return;
        }
        String key = ROOM_KEY_PREFIX + roomId;
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("deleteRoomMessages failed: roomId={}, err={}", roomId, e.getMessage());
        }
    }
}


