package com.gamehub.gameservice.platform.ongoing;

import com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys;
import com.gamehub.gameservice.infrastructure.redis.RedisOps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 负责记录/查询用户正在进行中的游戏。
 * 目前实现是 Redis String，TTL 与房间生命周期保持一致（48h）。
 */
@Component
@RequiredArgsConstructor
public class OngoingGameTracker {

    private static final Duration TTL = Duration.ofHours(48);

    private final RedisOps redisOps;

    public void save(String userId, OngoingGameInfo info) {
        if (userId == null || userId.isBlank() || info == null) {
            return;
        }
        redisOps.setEx(RedisKeys.userOngoing(userId), info, TTL);
    }

    public Optional<OngoingGameInfo> find(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisOps.get(RedisKeys.userOngoing(userId), OngoingGameInfo.class));
    }

    public void clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        redisOps.del(RedisKeys.userOngoing(userId));
    }
}

