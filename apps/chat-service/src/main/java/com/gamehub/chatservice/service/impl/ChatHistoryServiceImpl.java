package com.gamehub.chatservice.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.chatservice.entity.ChatMessage;
import com.gamehub.chatservice.service.ChatHistoryService;
import com.gamehub.chatservice.service.ChatSessionService;
import com.gamehub.chatservice.service.UserProfileCacheService;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryServiceImpl implements ChatHistoryService {

    /**
     * Redis List Key 前缀：chat:room:history:{roomId}
     */
    private static final String ROOM_KEY_PREFIX = "chat:room:history:";
    /**
     * Redis List Key 前缀：chat:private:history:{sessionId}
     * sessionId 由两个用户ID按字典序排序后拼接：userId1 < userId2 ? "userId1:userId2" : "userId2:userId1"
     */
    private static final String PRIVATE_KEY_PREFIX = "chat:private:history:";
    /**
     * 默认最多保留的消息条数（房间）
     */
    private static final int DEFAULT_MAX_SIZE = 50;
    /**
     * 默认最多保留的私聊消息条数
     */
    private static final int DEFAULT_PRIVATE_MAX_SIZE = 100;
    /**
     * 默认历史记录 TTL：24 小时
     */
    private static final long DEFAULT_TTL_SECONDS = 24 * 60 * 60;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;
    private final UserProfileCacheService userProfileCacheService;

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

    @Override
    public void appendPrivateMessage(ChatMessagePayload payload, int maxSize) {
        if (payload == null || !StringUtils.hasText(payload.getSenderId()) 
                || !StringUtils.hasText(payload.getTargetUserId())) {
            return;
        }
        
        // 生成会话ID：按用户ID字典序排序，确保双方使用相同的key
        String sessionId = buildPrivateSessionId(payload.getSenderId(), payload.getTargetUserId());
        String key = PRIVATE_KEY_PREFIX + sessionId;
        
        try {
            String json = objectMapper.writeValueAsString(payload);
            stringRedisTemplate.opsForList().rightPush(key, json);
            int keep = maxSize > 0 ? maxSize : DEFAULT_PRIVATE_MAX_SIZE;
            stringRedisTemplate.opsForList().trim(key, -keep, -1);
            stringRedisTemplate.expire(key, DEFAULT_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("appendPrivateMessage failed: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    @Override
    public List<ChatMessagePayload> listPrivateMessages(String userId1, String userId2, int limit) {
        Assert.hasText(userId1, "userId1 required");
        Assert.hasText(userId2, "userId2 required");
        
        String sessionId = buildPrivateSessionId(userId1, userId2);
        String key = PRIVATE_KEY_PREFIX + sessionId;
        int fetch = limit > 0 ? limit : DEFAULT_PRIVATE_MAX_SIZE;
        
        // 1. 先尝试从Redis读取
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size != null && size > 0) {
            long start = Math.max(-fetch, -size);
            List<String> raw = stringRedisTemplate.opsForList().range(key, start, -1);
            if (raw != null && !raw.isEmpty()) {
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
        }
        
        // 2. Redis为空，从数据库查询并回填Redis
        try {
            UUID user1Uuid = UUID.fromString(userId1);
            UUID user2Uuid = UUID.fromString(userId2);
            
            // 查询数据库中的sessionId（UUID格式）
            UUID dbSessionId = chatSessionService.getPrivateSessionId(user1Uuid, user2Uuid);
            if (dbSessionId == null) {
                // 会话不存在，返回空列表
                log.debug("私聊会话不存在: userId1={}, userId2={}", userId1, userId2);
                return Collections.emptyList();
            }
            
            // 从数据库查询消息（最多fetch条）
            List<ChatMessage> dbMessages = chatSessionService.listMessages(dbSessionId, fetch);
            if (dbMessages == null || dbMessages.isEmpty()) {
                log.debug("数据库中没有消息: sessionId={}", dbSessionId);
                return Collections.emptyList();
            }
            
            // 转换为ChatMessagePayload并写回Redis
            List<ChatMessagePayload> result = new ArrayList<>(dbMessages.size());
            for (ChatMessage msg : dbMessages) {
                try {
                    // 确定targetUserId（另一个用户）
                    // 如果发送者是user1，则接收者是user2；反之亦然
                    String targetUserId = msg.getSenderId().equals(user1Uuid) ? userId2 : userId1;
                    
                    // 解析发送者显示名称
                    String senderName = resolveDisplayName(msg.getSenderId().toString());
                    
                    ChatMessagePayload payload = ChatMessagePayload.builder()
                            .type("PRIVATE")
                            .roomId(null)
                            .senderId(msg.getSenderId().toString())
                            .senderName(senderName)
                            .targetUserId(targetUserId)
                            .content(msg.getContent())
                            .timestamp(msg.getCreatedAt().toInstant().toEpochMilli())
                            .clientOpId(msg.getClientOpId())
                            .build();
                    
                    result.add(payload);
                } catch (Exception e) {
                    log.warn("转换消息失败: messageId={}, err={}", msg.getId(), e.getMessage());
                }
            }
            
            // 写回Redis（批量写入，保持时间顺序）
            if (!result.isEmpty()) {
                try {
                    // 先清空（如果存在），然后批量写入
                    stringRedisTemplate.delete(key);
                    for (ChatMessagePayload payload : result) {
                        String json = objectMapper.writeValueAsString(payload);
                        stringRedisTemplate.opsForList().rightPush(key, json);
                    }
                    // 截断到指定大小并设置TTL
                    int keep = fetch > 0 ? fetch : DEFAULT_PRIVATE_MAX_SIZE;
                    stringRedisTemplate.opsForList().trim(key, -keep, -1);
                    stringRedisTemplate.expire(key, DEFAULT_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    log.debug("从数据库加载并回填Redis: sessionId={}, messageCount={}", dbSessionId, result.size());
                } catch (Exception e) {
                    log.warn("回填Redis失败: sessionId={}, err={}", dbSessionId, e.getMessage());
                    // 即使回填失败，也返回结果
                }
            }
            
            return result;
        } catch (IllegalArgumentException e) {
            log.warn("无效的用户ID格式: userId1={}, userId2={}, err={}", userId1, userId2, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("从数据库查询私聊历史失败: userId1={}, userId2={}", userId1, userId2, e);
            return Collections.emptyList();
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

    /**
     * 构建私聊会话ID
     * 按用户ID字典序排序后拼接，确保双方使用相同的key
     * 例如：userId1="abc", userId2="def" -> "abc:def"
     *      userId1="def", userId2="abc" -> "abc:def"（相同结果）
     *
     * @param userId1 用户1的Keycloak用户ID
     * @param userId2 用户2的Keycloak用户ID
     * @return 会话ID
     */
    private String buildPrivateSessionId(String userId1, String userId2) {
        // 按字典序排序，确保无论传入顺序如何，都生成相同的会话ID
        if (userId1.compareTo(userId2) <= 0) {
            return userId1 + ":" + userId2;
        } else {
            return userId2 + ":" + userId1;
        }
    }
}


