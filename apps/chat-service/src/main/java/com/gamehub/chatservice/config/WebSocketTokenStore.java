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
     * 保存 token（以 sessionId 为 key）
     * TTL: 1 小时（与 JWT token 过期时间一致）
     */
    public void putToken(String sessionId, String token) {
        if (sessionId == null || token == null) {
            return;
        }
        
        if (redisAvailable) {
            try {
                String key = TOKEN_KEY_PREFIX + sessionId;
                redis.opsForValue().set(key, token, DEFAULT_TTL.toSeconds(), TimeUnit.SECONDS);
                log.debug("已保存 token 到 Redis, sessionId={}, key={}", sessionId, key);
                return;
            } catch (Exception e) {
                log.warn("Redis 保存失败，降级到内存存储, sessionId={}, error={}", sessionId, e.getMessage());
                redisAvailable = false;
            }
        }
        
        // 降级到内存存储
        fallbackStore.put(sessionId, token);
        log.debug("已保存 token 到内存存储（降级）, sessionId={}", sessionId);
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
                if (token != null) {
                    log.debug("从 Redis 获取 token, sessionId={}, key={}", sessionId, key);
                    return token;
                } else {
                    log.debug("Redis 中没有 token, sessionId={}, key={}", sessionId, key);
                }
            } catch (Exception e) {
                log.warn("Redis 获取失败，尝试从内存存储获取, sessionId={}, error={}", sessionId, e.getMessage());
                redisAvailable = false;
            }
        }
        
        // 降级到内存存储
        String token = fallbackStore.get(sessionId);
        if (token != null) {
            log.debug("从内存存储获取 token（降级）, sessionId={}", sessionId);
        } else {
            log.debug("内存存储中也没有 token, sessionId={}", sessionId);
        }
        return token;
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
                log.debug("已移除 token, sessionId={}, key={}", sessionId, key);
                return;
            } catch (Exception e) {
                log.warn("Redis 删除失败，从内存存储删除, sessionId={}, error={}", sessionId, e.getMessage());
                redisAvailable = false;
            }
        }
        
        // 降级到内存存储
        fallbackStore.remove(sessionId);
        log.debug("已从内存存储移除 token（降级）, sessionId={}", sessionId);
    }
}

