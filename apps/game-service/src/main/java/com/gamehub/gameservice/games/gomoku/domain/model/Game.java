package com.gamehub.gameservice.games.gomoku.domain.model;

import lombok.Data;
import java.util.concurrent.ScheduledFuture;

/**
 *  一局游戏实体
 * 为现有 Service/控制器保留 index/gameId/pendingAi 等挂点。
 */
@Data
public class Game {
    private String gameId;     // UUID
    private final int    index;
    private final GomokuState state; // 本盘棋局
    private volatile ScheduledFuture<?> pendingAi; // 本盘的AI定时任务（方便取消）
    public Game(int idx,String gameId) {
        this.index = idx;
        this.gameId = gameId;
        this.state = new GomokuState();
        this.state.setCurrent(Board.BLACK); // 新盘默认黑先
    }
}
