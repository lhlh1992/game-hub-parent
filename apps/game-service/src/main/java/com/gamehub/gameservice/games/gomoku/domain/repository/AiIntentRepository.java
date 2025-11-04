package com.gamehub.gameservice.games.gomoku.domain.repository;

import com.gamehub.gameservice.games.gomoku.domain.dto.AiIntent;

import java.time.Duration;
import java.util.Optional;

/**
 * AiIntentRepository
 * ----------------------------------------
 * AI 意图仓储接口（AI-intent Repository）
 * - 存储 AI 的计划行动信息；
 * - 用于节点重启或负载均衡时的恢复；
 * - 当前实现为 Redis。
 * ----------------------------------------
 */
public interface AiIntentRepository {
    /**
     * 保存 AI 意图
     * @param roomId 房间ID
     * @param intent AI 意图对象（含 side/scheduledAtMs）
     * @param ttl    过期时间
     */
    void save(String roomId, AiIntent intent, Duration ttl);
    /**
     * 获取 AI 意图
     * @param roomId 房间ID
     * @return 可选的 AiIntent
     */
    Optional<AiIntent> get(String roomId);
    /**
     * 删除 AI 意图
     * @param roomId 房间ID
     */
    void delete(String roomId);
}
