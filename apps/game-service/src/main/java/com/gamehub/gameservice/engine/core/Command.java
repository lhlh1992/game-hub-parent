package com.gamehub.gameservice.engine.core;

/**
 * 统一的“玩家/AI 输入指令”抽象。回合制/实时制都用这个表示一条输入。
 * 游戏命令（玩家或AI的输入）。
 * - 回合制可以忽略 frame（返回0即可）；
 * - 实时制（RTS/格斗）用 frame 绑定该输入对应的帧号。
 * -以后无论是“落子(7,7)”还是“单位移动到(x,y)”都可以被封装为一个 Command。
 * -传输层（WebSocket/REST）只需序列化 Command，对具体游戏透明。
 */
public interface Command {

    long playerId();

    long frame();   // 回合制可返回 0；实时制按帧号推进
}
