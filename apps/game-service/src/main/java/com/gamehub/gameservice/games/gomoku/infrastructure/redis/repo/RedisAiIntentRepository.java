package com.gamehub.gameservice.games.gomoku.infrastructure.redis.repo;

import com.gamehub.gameservice.games.gomoku.domain.repository.AiIntentRepository;
import com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys;
import com.gamehub.gameservice.games.gomoku.domain.dto.AiIntent;
import com.gamehub.gameservice.infrastructure.redis.RedisOps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * RedisAiIntentRepository
 * -------------------------------------------------------
 * AI 行动意图的 Redis 仓储实现。
 * - 用于重启后的恢复：是否需要安排一次 AI 落子。
 */
@Repository
@RequiredArgsConstructor
public class RedisAiIntentRepository implements AiIntentRepository {

    private final RedisOps ops;

    /**
     * 保存 AI 意图（JSON 存储，带 TTL）
     */
    @Override
    public void save(String roomId, AiIntent intent, Duration ttl) {
        ops.setEx(RedisKeys.aiPending(roomId), intent, ttl);
    }

    /**
     * 获取 AI 意图
     */
    @Override
    public Optional<AiIntent> get(String roomId) {
        AiIntent i = ops.get(RedisKeys.aiPending(roomId), AiIntent.class);
        return Optional.ofNullable(i);
    }

    /**
     * 删除 AI 意图
     */
    @Override
    public void delete(String roomId) {
        ops.del(RedisKeys.aiPending(roomId));
    }
}
