package com.gamehub.gameservice.application.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 用户档案缓存（共享 session Redis）：game-service 侧读取。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileCacheService {

    private static final String KEY_PREFIX = "user:profile:";
    // 拉长 TTL，降低缓存穿透；资料变更由 system-service 刷新/驱逐
    private static final Duration TTL = Duration.ofHours(2);

    @Qualifier("sessionRedisTemplate")
    private final RedisTemplate<String, String> sessionRedisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<UserProfileView> get(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = sessionRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, UserProfileView.class));
        } catch (Exception e) {
            log.warn("读取用户档案缓存失败 userId={}", userId, e);
            return Optional.empty();
        }
    }

    public void put(UserProfileView userInfo) {
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
}

