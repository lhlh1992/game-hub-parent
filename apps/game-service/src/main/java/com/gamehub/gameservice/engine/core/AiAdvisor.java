package com.gamehub.gameservice.engine.core;

/**
 * AI 建议器抽象：给定状态，返回一条建议的 Command（例如：五子棋的“建议落在(7,8)”）。
 * AI 建议器：根据当前状态给出一个建议的命令（如下一步走法）。
 * - budgetMs：时间预算（毫秒），AI可以自行在内部做迭代加深等。
 * - 泛型 S、C 保持与游戏解耦。
 * 作用：
 * -五子棋的 GomokuAI 可以实现/适配该接口；
 * -后面扑克/RTS/格斗同样用此接口暴露“给我一招”。
 */
public interface AiAdvisor<S extends GameState, C extends Command> {

    C suggest(S state, long budgetMs);
}
