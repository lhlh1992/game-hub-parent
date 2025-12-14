package com.gamehub.chatservice.config;

import com.gamehub.session.config.SessionRedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket Token 存储（Redis 版本，带内存降级）
 * 
 * 在 CONNECT 时保存原始 JWT Token 到 Redis，供后续消息处理时使用。
 * 支持多实例部署：所有实例共享同一个 Redis db0，可以跨实例访问 token。
 * 
 * 降级策略：如果 Redis 不可用或 bean 不存在，自动降级到内存存储（仅支持单实例）。
 * 
 * Redis Key 格式：ws:token:{sessionId}
 * TTL: 1 小时（与 JWT token 过期时间一致）
 */
@Component
@Slf4j
public class WebSocketTokenStore {
    
    private static final String TOKEN_KEY_PREFIX = "ws:token:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1); // JWT token 通常 1 小时过期
    
    private final RedisTemplate<String, String> redis;
    private final ConcurrentHashMap<String, String> fallbackStore = new ConcurrentHashMap<>(); // 降级存储
    private volatile boolean redisAvailable = false; // Redis 可用性标记
    
    /**
     * 使用 session-common 提供的 RedisTemplate（连接到 session.redis.database: 0）
     * 所有实例共享同一个 Redis db0，支持多实例部署
     * 
     * 如果 sessionRedisTemplate bean 不存在，redis 为 null，使用内存存储
     */
    public WebSocketTokenStore(
        @Autowired(required = false)
        @Qualifier(SessionRedisConfig.SESSION_REDIS_TEMPLATE_BEAN) 
        RedisTemplate<String, String> redis
    ) {
        this.redis = redis;
        // 测试 Redis 连接
        if (redis != null) {
            testRedisConnection();
        } else {
            log.warn("sessionRedisTemplate bean 不存在，使用内存存储（仅支持单实例）");
            redisAvailable = false;
        }
    }
    
    /**
     * 测试 Redis 连接是否可用
     */
    private void testRedisConnection() {
        if (redis == null) {
            redisAvailable = false;
            return;
        }
        try {
            redis.hasKey("__test__");
            log.info("Redis 连接正常，使用 Redis 存储 token（支持多实例）");
            redisAvailable = true;
        } catch (Exception e) {
            log.warn("Redis 连接失败，降级到内存存储（仅支持单实例）: {}", e.getMessage());
            redisAvailable = false;
        }
    }
    
    /**
     * 检查 Redis 连接状态（用于诊断）
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }
    
    /**
     * 保存 token（以 sessionId 为 key）
     * TTL: 1 小时（与 JWT token 过期时间一致）
     * 
     * 策略：同时写入 Redis 和内存存储（双重保障）
     * - Redis：支持多实例，自动过期
     * - 内存：降级保障，即使 Redis 失败也能工作
     */
    public void putToken(String sessionId, String token) {
        if (sessionId == null || token == null) {
            return;
        }
        
        // 策略：同时写入 Redis 和内存存储（双重保障）
        if (redisAvailable) {
            try {
                String key = TOKEN_KEY_PREFIX + sessionId;
                redis.opsForValue().set(key, token, DEFAULT_TTL.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Redis 保存失败，将使用内存存储, sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        
        // 无论 Redis 是否成功，都写入内存存储（降级保障）
        fallbackStore.put(sessionId, token);
    }
    
    /**
     * 获取 token
     */
    public String getToken(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        
        if (redisAvailable) {
            try {
                String key = TOKEN_KEY_PREFIX + sessionId;
                String token = redis.opsForValue().get(key);
                if (token != null && !token.isBlank()) {
                    return token;
                }
            } catch (Exception e) {
                log.warn("Redis 获取失败，尝试从内存存储获取, sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        
        // 降级到内存存储
        return fallbackStore.get(sessionId);
    }
    
    /**
     * 移除 token（连接断开时调用）
     */
    public void removeToken(String sessionId) {
        if (sessionId == null) {
            return;
        }
        
        if (redisAvailable) {
            try {
                String key = TOKEN_KEY_PREFIX + sessionId;
                redis.delete(key);
            } catch (Exception e) {
                log.warn("Redis 删除失败, sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        
        fallbackStore.remove(sessionId);
    }
}

