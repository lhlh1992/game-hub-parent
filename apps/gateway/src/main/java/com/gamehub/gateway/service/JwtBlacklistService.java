package com.gamehub.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 网关侧黑名单服务：负责将被撤销的 JWT 写入 Redis，并提供查询接口。
 * 逻辑非常简单——只有写和查，实质上是一个带 TTL 的 Key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";
    private static final long DEFAULT_TTL_SECONDS = 3600;

    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 将 token 写入黑名单。TTL 选取 token 剩余有效期（兜底 1 小时）。
     */
    public Mono<Void> addToBlacklist(String token, long expiresInSeconds) {
        if (token == null || token.isBlank()) {
            return Mono.empty();
        }
        long ttl = expiresInSeconds > 0 ? expiresInSeconds : DEFAULT_TTL_SECONDS;
        return redisTemplate.opsForValue()
                .set(buildKey(token), "1", Duration.ofSeconds(ttl))
                .doOnSuccess(success -> {
                    if (Boolean.FALSE.equals(success)) {
                        log.warn("Redis set 返回 false，token={}", token);
                    }
                })
                .doOnError(ex -> log.error("写入 JWT 黑名单失败", ex))
                .onErrorResume(ex -> Mono.empty())
                .then();
    }

    /**
     * 查询 token 是否已被撤销。出错时默认视为命中，宁可拒绝也不放过。
     */
    public Mono<Boolean> isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey(buildKey(token))
                .onErrorResume(ex -> {
                    log.error("查询 JWT 黑名单失败，默认视为命中", ex);
                    return Mono.just(true);
                });
    }

    private static String buildKey(String token) {
        return BLACKLIST_KEY_PREFIX + token;
    }
}

