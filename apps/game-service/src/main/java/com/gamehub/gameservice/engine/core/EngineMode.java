package com.gamehub.gameservice.engine.core;

/**
 * 引擎运行模式：
 * TURN_BASED：回合制（五子棋/象棋/卡牌）。
 * REALTIME  ：实时制（RTS、格斗/街机）。
 * -作用：
 * 后面我们做“房间Actor”时，会根据模式决定是否需要固定帧 tick。五子棋属于 TURN_BASED。
 */
public enum EngineMode {
    /**
     * 回合制（五子棋/象棋/卡牌）
     */
    TURN_BASED,
    /**
     * 实时制（RTS、格斗/街机）。
     */
    REALTIME
}
