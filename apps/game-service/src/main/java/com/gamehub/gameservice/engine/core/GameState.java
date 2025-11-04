package com.gamehub.gameservice.engine.core;

/**
 * 游戏状态快照接口。
 * - 必须可 copy：便于回放、AI 搜索、保存/恢复等。
 * - 具体游戏（如 GomokuState）实现此接口。
 * - 便于ai搜索：copy对局去模拟出招，找出胜率最大的走法
 */
public interface GameState extends Cloneable{

    /**
     * 返回当前状态的深拷贝快照。
     */
    GameState copy();
}
