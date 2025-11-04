package com.gamehub.gameservice.games.gomoku.domain.ai;

import com.gamehub.gameservice.games.gomoku.domain.model.Board;


/**
 * 棋局评估函数
 * 启发式评估：给局面打分（相对某一方 me）。
 * 分越大表示越有利于 me。
 *
 * 先用简单但好用的“连子长度评分”，后续可替换为模式表（活四/冲四/活三...）。
 */
public class Evaluator {

    /**
     * 评估当前棋盘局势（启发式估值函数）。
     * 遍历全盘所有棋子，计算我方与对方的形势得分（连子强度等），
     * 返回“我方总分 - 对方总分”，供AI搜索时比较局面优劣。
     */
    public static int score(Board b, char me) {
        int meScore = 0, oppScore = 0;
        char opp = (me == Board.BLACK ? Board.WHITE : Board.BLACK);

        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                char p = b.get(x, y);
                if (p == Board.EMPTY) continue;
                int s = localPotential(b, x, y, p);
                if (p == me) meScore += s; else oppScore += s;
            }
        }
        return meScore - oppScore;
    }

    /** 单点四向潜力分（用于候选排序） */
    public static int localPotential(Board b, int x, int y, char piece) {
        int s = 0;
        int[][] dirs = {{1,0},{0,1},{1,1},{1,-1}};
        for (int[] d : dirs) s += linePatternScore(b, x, y, d[0], d[1], piece, true);
        return s;
    }

    // ============== 内部实现 ==============

    private static int cellScore(Board b, int x, int y, char piece) {
        int s = 0;
        int[][] dirs = {{1,0},{0,1},{1,1},{1,-1}};
        for (int[] d : dirs) s += linePatternScore(b, x, y, d[0], d[1], piece, false);
        return s;
    }

    /**
     * 单方向模式评分：
     * includeEnds=true：站在“落子点”的视角统计两端开放度
     * false：用于已落子评分，忽略两端空位加成
     */
    private static int linePatternScore(Board b, int x, int y, int dx, int dy, char piece, boolean includeEnds) {
        int count = 1, open = 0;

        // 正向
        int cx = x + dx, cy = y + dy;
        while (b.inBounds(cx, cy) && b.get(cx, cy) == piece) { count++; cx += dx; cy += dy; }
        if (includeEnds && b.inBounds(cx, cy) && b.get(cx, cy) == Board.EMPTY) open++;

        // 反向
        cx = x - dx; cy = y - dy;
        while (b.inBounds(cx, cy) && b.get(cx, cy) == piece) { count++; cx -= dx; cy -= dy; }
        if (includeEnds && b.inBounds(cx, cy) && b.get(cx, cy) == Board.EMPTY) open++;

        // 分值（可微调）
        if (count >= 5) return 100000;          // 成五
        if (count == 4) {
            if (open == 2) return 7000;         // 活四
            if (open == 1) return 1200;         // 冲四
            return 0;
        }
        if (count == 3) {
            if (open == 2) return 600;          // 活三
            if (open == 1) return 120;          // 眠三
            return 0;
        }
        if (count == 2) {
            if (open == 2) return 60;           // 活二
            if (open == 1) return 12;           // 眠二
            return 0;
        }
        return 3;                                // 单子，微弱奖励
    }
}
