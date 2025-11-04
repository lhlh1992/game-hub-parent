package com.gamehub.gameservice.games.gomoku.infrastructure.redis.repo;

import com.gamehub.gameservice.games.gomoku.domain.dto.TurnAnchor;
import com.gamehub.gameservice.games.gomoku.domain.repository.GameStateRepository;
import com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys;
import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;
import com.gamehub.gameservice.infrastructure.redis.RedisOps;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * RedisGameStateRepository
 * -------------------------------------------------------
 * 单盘棋局状态的 Redis 仓储实现。
 * - 存取对象：GameStateRecord；
 * - 建议使用合适 TTL（例如房间活跃期 + 24h）。
 */
@Repository
@RequiredArgsConstructor
public class RedisGameStateRepository implements GameStateRepository {

    private final RedisOps ops;

    private final RedisTemplate<String, Object> redisTemplate; // ★ 新增注入，用于原生事务

    /**
     * 保存棋局状态（JSON 存储，带 TTL）
     */
    @Override
    public void save(String roomId, String gameId, GameStateRecord state, Duration ttl) {
        ops.setEx(RedisKeys.gameState(roomId, gameId), state, ttl);
    }

    /**
     * 获取棋局状态
     */
    @Override
    public Optional<GameStateRecord> get(String roomId, String gameId) {
        GameStateRecord r = ops.get(RedisKeys.gameState(roomId, gameId), GameStateRecord.class);
        return Optional.ofNullable(r);
    }

    /**
     * 删除棋局状态
     */
    @Override
    public void delete(String roomId, String gameId) {
        ops.del(RedisKeys.gameState(roomId, gameId));
    }

    /**
     * 原子更新当前盘面状态 + 回合锚点（CAS 语义）。
     * <p>
     * 实现：对棋局状态键执行 WATCH，校验期望步数与期望轮到方一致后，
     * 在事务（MULTI/EXEC）中同时写入新的 GameStateRecord 以及 TurnAnchor（下一回合方与截止时间）。
     * 若在提交前键被其他请求修改，EXEC 返回 null，视为更新失败。
     *
     * @param roomId             房间ID
     * @param gameId             盘ID（同一房间可多盘）
     * @param expectedStep       期望的步数（CAS 校验值）
     * @param expectedTurn       期望的轮到方（'X'/'O'，CAS 校验值）
     * @param newState           要写入的最新棋局快照
     * @param newDeadlineMillis  下一回合的绝对截止时间（毫秒），终局时可为 0
     * @return true 表示更新成功；false 表示校验不通过或事务冲突
     */
    @Override
    public boolean updateAtomically(
            String roomId,
            String gameId,
            int expectedStep,
            char expectedTurn,
            GameStateRecord newState,
            long newDeadlineMillis) {

        final String stateKey = RedisKeys.gameState(roomId, gameId);
        final String turnKey  = RedisKeys.turnAnchor(roomId);

        Boolean ok = redisTemplate.execute(new SessionCallback<Boolean>() {
            @SuppressWarnings("unchecked")
            @Override
            public <K, V> Boolean execute(RedisOperations<K, V> operations) throws DataAccessException {
                // 1) 监视 stateKey
                operations.watch((K) stateKey);

                // 2) 读取并校验当前状态
                GameStateRecord cur = (GameStateRecord) operations.opsForValue().get((K) stateKey);
                if (cur == null) {
                    operations.unwatch();
                    return false;
                }
                int curStep = cur.getStep() == null ? 0 : cur.getStep();
                if (curStep != expectedStep) {
                    operations.unwatch();
                    return false;
                }
                char curTurn = cur.getCurrent() == null ? 0 : cur.getCurrent().charAt(0);
                if (curTurn != expectedTurn) {
                    operations.unwatch();
                    return false;
                }

                // 3) 开启事务、写入新状态 + 回合锚点
                operations.multi();

                operations.opsForValue().set((K) stateKey, (V) newState);

                // 下个回合方（终局则 null），只做展示/倒计时锚点
                String nextSideStr = newState.isOver()
                        ? null
                        : (newState.getCurrent() == null ? null : String.valueOf(newState.getCurrent().charAt(0)));

                TurnAnchor anchor = new TurnAnchor();          // 无参构造
                anchor.setRoomId(roomId);                      // 你类里有这两个字段
                anchor.setGameId(gameId);
                anchor.setSide(nextSideStr);                   // "X"/"O" 或 null
                anchor.setDeadlineEpochMs(newDeadlineMillis);  // 截止时间（毫秒）
                // 如需自增 turnSeq，可在这里计算后设置：anchor.setTurnSeq( Optional.ofNullable(anchor.getTurnSeq()).orElse(0L) + 1 );
                operations.opsForValue().set((K) turnKey, (V) anchor);

                // 4) 提交事务：exec 返回 null 代表被改动冲突
                var res = operations.exec();
                return res != null;
            }
        });

        return Boolean.TRUE.equals(ok);
    }


}
