package com.gamehub.gameservice.games.gomoku.infrastructure.redis.repo;

import com.gamehub.gameservice.games.gomoku.domain.repository.TurnRepository;
import com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys;
import com.gamehub.gameservice.games.gomoku.domain.dto.TurnAnchor;
import com.gamehub.gameservice.infrastructure.redis.RedisOps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * RedisTurnRepository
 * -------------------------------------------------------
 * 回合计时锚点的 Redis 仓储实现。
 * - 仅保存“锚点”信息（deadline/side/turnSeq），
 *   不保存运行态的定时任务句柄。
 */
@Repository
@RequiredArgsConstructor
public class RedisTurnRepository implements TurnRepository {

    private final RedisOps ops;

    /**
     * 保存回合计时锚点（JSON 存储，带 TTL）
     */
    @Override
    public void save(String roomId, TurnAnchor anchor, Duration ttl) {
        ops.setEx(RedisKeys.turnAnchor(roomId), anchor, ttl);
    }

    /**
     * 获取回合计时锚点
     */
    @Override
    public Optional<TurnAnchor> get(String roomId) {
        TurnAnchor a = ops.get(RedisKeys.turnAnchor(roomId), TurnAnchor.class);
        return Optional.ofNullable(a);
    }

    /**
     * 删除回合计时锚点
     */
    @Override
    public void delete(String roomId) {
        ops.del(RedisKeys.turnAnchor(roomId));
    }
}
