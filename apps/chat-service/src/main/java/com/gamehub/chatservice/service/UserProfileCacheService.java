package com.gamehub.chatservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileCacheService {

    private static final String KEY_PREFIX = "user:profile:";
    // 用户档案缓存 TTL 拉长到 2 小时，减少高频刷新；变更由 system-service 驱逐/刷新
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
        if (userInfo == null || userInfo.userId() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(userInfo);
            sessionRedisTemplate.opsForValue().set(KEY_PREFIX + userInfo.userId(), json, TTL);
        } catch (Exception e) {
            log.warn("写入用户档案缓存失败 userId={}", userInfo.userId(), e);
        }
    }

    /**
     * 批量获取用户信息（优先从缓存，缓存未命中时从远程服务获取并更新缓存）
     * 使用并行处理提高性能
     *
     * @param userIds 用户ID列表
     * @param remoteFetcher 远程获取函数（缓存未命中时调用）
     * @return 用户信息Map，key为userId，value为用户信息（可能为null）
     */
    public Map<String, UserProfileView> batchGet(List<String> userIds, 
                                                  java.util.function.Function<String, UserProfileView> remoteFetcher) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 去重
        List<String> distinctUserIds = userIds.stream().distinct().collect(Collectors.toList());
        
        // 先批量从缓存获取
        Map<String, UserProfileView> result = new ConcurrentHashMap<>();
        List<String> cacheMissUserIds = new ArrayList<>();

        for (String userId : distinctUserIds) {
            Optional<UserProfileView> cached = get(userId);
            if (cached.isPresent()) {
                result.put(userId, cached.get());
            } else {
                cacheMissUserIds.add(userId);
            }
        }

        // 缓存未命中的，并行从远程服务获取（使用并行流，避免线程池配置问题）
        if (!cacheMissUserIds.isEmpty()) {
            cacheMissUserIds.parallelStream().forEach(userId -> {
                try {
                    UserProfileView userInfo = remoteFetcher.apply(userId);
                    if (userInfo != null) {
                        // 更新缓存
                        put(userInfo);
                        result.put(userId, userInfo);
                    }
                } catch (Exception e) {
                    log.warn("批量获取用户信息失败: userId={}", userId, e);
                    // 不中断其他用户的获取
                }
            });
        }

        return result;
    }

    public record UserProfileView(String userId, String username, String nickname, String avatarUrl) {}
}

