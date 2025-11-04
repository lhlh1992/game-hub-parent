package com.gamehub.gameservice.games.gomoku.domain.repository;

import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;

import java.time.Duration;
import java.util.Optional;

/**
 * GameStateRepository
 * ----------------------------------------
 * 单盘棋局状态仓储接口（Game-level Repository）
 * - 管理单盘的权威棋局状态；
 * - 支持保存、查询与删除；
 * - 当前实现基于 Redis，未来可扩展为数据库。
 * ----------------------------------------
 */
public interface GameStateRepository {

    /**
     * 保存棋局状态（权威状态）
     * @param roomId 房间ID
     * @param gameId 棋局ID
     * @param state  棋局状态对象
     * @param ttl    过期时间
     */
    void save(String roomId, String gameId, GameStateRecord state, Duration ttl);
    /**
     * 获取棋局状态
     * @param roomId 房间ID
     * @param gameId 棋局ID
     * @return 可选的 GameStateRecord（不存在则 empty）
     */
    Optional<GameStateRecord> get(String roomId, String gameId);

    /**
     * 删除棋局状态
     * @param roomId 房间ID
     * @param gameId 棋局ID
     */
    void delete(String roomId, String gameId);


    // === 新增：基于 WATCH/MULTI 的原子更新 ===
    boolean updateAtomically(
            String roomId,
            String gameId,
            int expectedStep,
            char expectedTurn,
            GameStateRecord newState,
            long newDeadlineMillis);

}
