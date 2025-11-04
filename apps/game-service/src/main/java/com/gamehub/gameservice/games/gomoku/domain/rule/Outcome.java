package com.gamehub.gameservice.games.gomoku.domain.rule;

/** 对局结果：未结束 / 黑胜 / 白胜 / 和棋 */
public enum Outcome {
    /** 对局进行中（尚未分出胜负） */
    ONGOING,
    /** 黑方胜利 */
    BLACK_WIN,
    /** 白方胜利 */
    WHITE_WIN,
    /** 平局 */
    DRAW;

    /**
     * 根据棋子颜色判断胜方。
     *
     * @param piece 棋子符号，'X' 表示黑棋，'O' 表示白棋
     * @return 黑棋返回 {@link #BLACK_WIN}，白棋返回 {@link #WHITE_WIN}
     */
    public static Outcome winOf(char piece) {
        return (piece == 'X') ? BLACK_WIN : WHITE_WIN;
    }
}
