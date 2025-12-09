package com.gamehub.systemservice.service.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.systemservice.dto.response.UserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 用户档案缓存：跨服务共享，使用 session Redis。
 * 说明：复用 session-common 提供的字符串模板，值用 JSON 手动序列化，避免 Bean 冲突。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileCacheService {

    private static final String KEY_PREFIX = "user:profile:";
    private static final Duration TTL = Duration.ofMinutes(10);

    @Qualifier("sessionRedisTemplate")
    private final RedisTemplate<String, String> sessionRedisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<UserInfo> get(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = sessionRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, UserInfo.class));
        } catch (Exception e) {
            log.warn("读取用户档案缓存失败 userId={}", userId, e);
            return Optional.empty();
        }
    }

    public void put(UserInfo userInfo) {
        if (userInfo == null || userInfo.getUserId() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(userInfo);
            sessionRedisTemplate.opsForValue().set(KEY_PREFIX + userInfo.getUserId(), json, TTL);
        } catch (Exception e) {
            log.warn("写入用户档案缓存失败 userId={}", userInfo.getUserId(), e);
        }
    }

    public void evict(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        sessionRedisTemplate.delete(KEY_PREFIX + userId);
    }
}

