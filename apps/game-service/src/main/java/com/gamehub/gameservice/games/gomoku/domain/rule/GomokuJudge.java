package com.gamehub.gameservice.games.gomoku.domain.rule;

import com.gamehub.gameservice.games.gomoku.domain.model.Board;

/**
 * 核心规则判断
 * 五子棋规则判定（自由五子棋，不含禁手）。
 * 只包含纯判断逻辑：合法性、胜负、和棋。
 */
public class GomokuJudge {

    // 4 个方向：横、竖、主对角、反对角
    private static final int[][] DIRS = {
            {1, 0},  // →
            {0, 1},  // ↓
            {1, 1},  // ↘
            {1,-1}   // ↗
    };

    /** 该落点是否“棋盘内为空” */
    public static boolean isLegal(Board b, int x, int y) {
        return b.inBounds(x, y) && b.isEmpty(x, y);
    }

    /**
     * 是否形成五连（基于“最后一步”快速判断）。
     * @param b     棋盘
     * @param x,y   最后一步的坐标
     * @param piece 最后一步的棋子（'X' 或 'O'）
     */
    public static boolean isWin(Board b, int x, int y, char piece) {
        for (int[] d : DIRS) {
            int count = 1;
            //正方向
            count += countOneDir(b, x, y, d[0], d[1], piece);
            //反方向
            count += countOneDir(b, x, y, -d[0], -d[1], piece);
            if (count >= 5) return true;
        }
        return false;
    }

    /** 棋盘是否已满（用于和棋判断） */
    public static boolean isFull(Board b) {
        for (int i = 0; i < Board.SIZE; i++)
            for (int j = 0; j < Board.SIZE; j++)
                if (b.get(i, j) == Board.EMPTY) return false;
        return true;
    }

    /**
     * 根据“执行完这步棋”后的局面，返回对局结果。
     * （外层通常在 GameState.apply() 之后调用）
     */
    public static Outcome outcomeAfterMove(Board b, int x, int y, char piece) {
        if (isWin(b, x, y, piece)) {
            return Outcome.winOf(piece);
        }
        if (isFull(b)) {
            return Outcome.DRAW;
        }
        return Outcome.ONGOING;
    }

    // ----------- private helpers -----------
    /**
     * 沿某个方向数连续相同棋子（不含起点），直到越界或遇到不同棋子停止
     * @param b
     * @param x
     * @param y
     * @param dx   x偏移量
     * @param dy   y偏移量
     * @param piece
     * @return
     */
    private static int countOneDir(Board b, int x, int y, int dx, int dy, char piece) {
        int c = 0;
        x += dx; y += dy;
        while (b.inBounds(x, y) && b.get(x, y) == piece) {
            c++; x += dx; y += dy;
        }
        return c;
    }
}
