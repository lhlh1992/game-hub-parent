package com.gamehub.gameservice.games.gomoku.domain.repository;

import com.gamehub.gameservice.games.gomoku.domain.dto.TurnAnchor;

import java.time.Duration;
import java.util.Optional;

/**
 * TurnRepository
 * ----------------------------------------
 * 回合计时锚点仓储接口（Turn-level Repository）
 * - 保存每盘棋当前回合的计时锚点；
 * - 用于跨节点恢复倒计时状态；
 * - 当前实现为 Redis。
 * ----------------------------------------
 */
public interface TurnRepository {

    /**
     * 保存回合计时锚点
     * @param roomId 房间ID
     * @param anchor 锚点对象（含 side/turnSeq/deadlineEpochMs）
     * @param ttl    过期时间
     */
    void save(String roomId, TurnAnchor anchor, Duration ttl);
    /**
     * 获取回合计时锚点
     * @param roomId 房间ID
     * @return 可选的 TurnAnchor
     */
    Optional<TurnAnchor> get(String roomId);
    /**
     * 删除回合计时锚点
     * @param roomId 房间ID
     */
    void delete(String roomId);
}
