package com.gamehub.gameservice.games.gomoku.domain.ai;

import com.gamehub.gameservice.games.gomoku.domain.model.Board;
import com.gamehub.gameservice.games.gomoku.domain.model.Move;
import com.gamehub.gameservice.games.gomoku.domain.rule.GomokuJudge;
import com.gamehub.gameservice.games.gomoku.domain.rule.GomokuJudgeRenju;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * GomokuAI：
 * 1) 立即胜利优先（我方一下成五直接下）
 * 2) 立即防守优先（对方一下成五，立刻堵）
 * 3) 候选点只取邻近点，并按局部潜力排序
 * 4) α-β剪枝 + 启发式评估（见 Evaluator）
 */
public class GomokuAI {

    private final int maxDepth;
    private final boolean renju; // 是否启用连珠禁手（仅黑方生效）

    public GomokuAI(int maxDepth, boolean renju) {
        this.maxDepth = Math.max(1, maxDepth);
        this.renju = renju;
    }

    /** 计算对 me 的最佳一步（威胁优先 + 搜索；RENJU 时自动避开黑方禁手） */
    public Move bestMove(Board board, char me) {
        // 关键修复：空棋盘时，中心位置是 (7, 7)，在有效范围 0-13 内
        if (isEmptyBoard(board)) {
            int center = Board.SIZE / 2; // 15/2 = 7，在有效范围内
            return new Move(center, center, me);
        }

        // 1) 我方一步即胜
        Move winNow = findImmediateWinLegal(board, me);
        if (winNow != null) return winNow;

        char opp = (me == Board.BLACK ? Board.WHITE : Board.BLACK);

        // 2) 对方一步即胜（先堵）
        Move oppWin = findImmediateWinLegal(board, opp);
        if (oppWin != null) return new Move(oppWin.x(), oppWin.y(), me);

        // 2.5) 对方二步必杀前兆（活四 / 双活三）——提前卡位
        int[] threat = findOpponentThreat(board, opp);
        if (threat != null) return new Move(threat[0], threat[1], me);

        // 3) 候选点（战场外扩 pad=2，按潜力排序；RENJU+黑方过滤禁手）
        List<int[]> cands = candidates(board, me, opp);
        if (cands.isEmpty()) {
            // 关键修复：兜底位置使用中心点 (7, 7)，在有效范围 0-13 内
            int center = Board.SIZE / 2; // 15/2 = 7
            return new Move(center, center, me);
        }

        // 4) α-β搜索
        int bestScore = Integer.MIN_VALUE;
        Move best = null;
        for (int[] p : cands) {
            int x = p[0], y = p[1];
            if (isForbiddenPoint(board, x, y, me)) continue; // 源头规避禁手
            board.place(x, y, me);
            int score = GomokuJudge.isWin(board, x, y, me)
                    ? 1_000_000
                    : -alphaBeta(board, maxDepth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    opp, me);
            board.place(x, y, Board.EMPTY);
            if (score > bestScore) { bestScore = score; best = new Move(x, y, me); }
        }
        // 若全被禁手/剪枝过滤（极罕见），给一个就近合法点兜底
        if (best == null) {
            for (int[] p : cands) {
                if (!isForbiddenPoint(board, p[0], p[1], me)) {
                    best = new Move(p[0], p[1], me); break;
                }
            }
            if (best == null) {
                // 关键修复：兜底位置使用中心点 (7, 7)，在有效范围 0-13 内
                int center = Board.SIZE / 2; // 15/2 = 7
                best = new Move(center, center, me);
            }
        }
        return best;
    }

    /** α-β剪枝（cur 当前走子方；me AI 方） */
    private int alphaBeta(Board b, int depth, int alpha, int beta, char cur, char me) {
        if (depth == 0) return Evaluator.score(b, me);

        char opp = (cur == Board.BLACK ? Board.WHITE : Board.BLACK);
        List<int[]> cands = candidates(b, me, opp);
        for (int[] p : cands) {
            int x = p[0], y = p[1];
            if (isForbiddenPoint(b, x, y, cur)) continue; // 搜索中也要避禁手
            b.place(x, y, cur);
            int val = GomokuJudge.isWin(b, x, y, cur)
                    ? (cur == me ? 1_000_000 : -1_000_000)
                    : -alphaBeta(b, depth - 1, -beta, -alpha, opp, me);
            b.place(x, y, Board.EMPTY);
            if (val > alpha) alpha = val;
            if (alpha >= beta) return alpha; // 剪枝
        }
        return alpha;
    }

    // ================== 威胁优先 & 合法性 ==================

    /** 仅考虑"合法"的一步即胜（RENJU + 黑方禁手会被过滤） */
    private Move findImmediateWinLegal(Board b, char side) {
        // 已修复：允许在 0-14 的所有交叉点落子
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                if (!b.isEmpty(x, y)) continue;
                if (isForbiddenPoint(b, x, y, side)) continue;
                b.place(x, y, side);
                boolean win = GomokuJudge.isWin(b, x, y, side);
                b.place(x, y, Board.EMPTY);
                if (win) return new Move(x, y, side);
            }
        }
        return null;
    }

    /** 对方一步会形成活四或双活三 → 返回该威胁点（提前卡位） */
    private int[] findOpponentThreat(Board b, char opp) {
        // 已修复：允许在 0-14 的所有交叉点落子
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                if (!b.isEmpty(x, y)) continue;
                // 这里判断对方的威胁，不需要套我方禁手
                b.place(x, y, opp);
                boolean openFour = createsOpenFour(b, x, y, opp);
                int openThrees = countOpenThrees(b, x, y, opp);
                b.place(x, y, Board.EMPTY);
                if (openFour || openThrees >= 2) return new int[]{x, y};
            }
        }
        return null;
    }

    /** 是否黑方禁手点（仅在启用 RENJU + side==BLACK 时为真） */
    private boolean isForbiddenPoint(Board b, int x, int y, char side) {
        return renju && side == Board.BLACK && GomokuJudgeRenju.isForbiddenMove(b, x, y);
    }

    private boolean createsOpenFour(Board b, int x, int y, char piece) {
        int[][] dirs = {{1,0},{0,1},{1,1},{1,-1}};
        for (int[] d : dirs) {
            int cnt = 1 + count(b, x, y, d[0], d[1], piece)
                    + count(b, x, y, -d[0], -d[1], piece);
            int opens = openEnds(b, x, y, d[0], d[1], piece);
            if (cnt == 4 && opens == 2) return true; // 活四
        }
        return false;
    }

    private int countOpenThrees(Board b, int x, int y, char piece) {
        int total = 0;
        int[][] dirs = {{1,0},{0,1},{1,1},{1,-1}};
        for (int[] d : dirs) {
            int cnt = 1 + count(b, x, y, d[0], d[1], piece)
                    + count(b, x, y, -d[0], -d[1], piece);
            int opens = openEnds(b, x, y, d[0], d[1], piece);
            if (cnt == 3 && opens == 2 && canExtendToFour(b, x, y, d[0], d[1], piece)) {
                total++;
            }
        }
        return total;
    }

    private int count(Board b, int x, int y, int dx, int dy, char piece) {
        int c = 0; x += dx; y += dy;
        while (b.inBounds(x, y) && b.get(x, y) == piece) { c++; x += dx; y += dy; }
        return c;
    }

    private int openEnds(Board b, int x, int y, int dx, int dy, char piece) {
        int opens = 0;
        int cx = x + dx, cy = y + dy;
        while (b.inBounds(cx, cy) && b.get(cx, cy) == piece) { cx += dx; cy += dy; }
        if (b.inBounds(cx, cy) && b.get(cx, cy) == Board.EMPTY) opens++;
        cx = x - dx; cy = y - dy;
        while (b.inBounds(cx, cy) && b.get(cx, cy) == piece) { cx -= dx; cy -= dy; }
        if (b.inBounds(cx, cy) && b.get(cx, cy) == Board.EMPTY) opens++;
        return opens;
    }

    private boolean canExtendToFour(Board b, int x, int y, int dx, int dy, char piece) {
        int cx = x, cy = y;
        while (b.inBounds(cx + dx, cy + dy) && b.get(cx + dx, cy + dy) == piece) { cx += dx; cy += dy; }
        if (b.inBounds(cx + dx, cy + dy) && b.get(cx + dx, cy + dy) == Board.EMPTY) {
            b.place(cx + dx, cy + dy, piece);
            boolean ok = (1 + count(b, x, y, dx, dy, piece)
                    + count(b, x, y, -dx, -dy, piece)) >= 4;
            b.place(cx + dx, cy + dy, Board.EMPTY);
            if (ok) return true;
        }
        cx = x; cy = y;
        while (b.inBounds(cx - dx, cy - dy) && b.get(cx - dx, cy - dy) == piece) { cx -= dx; cy -= dy; }
        if (b.inBounds(cx - dx, cy - dy) && b.get(cx - dx, cy - dy) == Board.EMPTY) {
            b.place(cx - dx, cy - dy, piece);
            boolean ok = (1 + count(b, x, y, dx, dy, piece)
                    + count(b, x, y, -dx, -dy, piece)) >= 4;
            b.place(cx - dx, cy - dy, Board.EMPTY);
            if (ok) return true;
        }
        return false;
    }

    // ================== 候选点生成 ==================

    private boolean isEmptyBoard(Board b) {
        for (int i = 0; i < Board.SIZE; i++)
            for (int j = 0; j < Board.SIZE; j++)
                if (b.get(i, j) != Board.EMPTY) return false;
        return true;
    }

    /** 候选点：战场外扩 pad=2；必须有 8 邻域邻居；按潜力排序（攻 + 防） */
    private List<int[]> candidates(Board b, char me, char opp) {
        boolean hasStone = false;
        int minX = Board.SIZE, minY = Board.SIZE, maxX = -1, maxY = -1;

        for (int i = 0; i < Board.SIZE; i++) {
            for (int j = 0; j < Board.SIZE; j++) {
                if (b.get(i, j) != Board.EMPTY) {
                    hasStone = true;
                    if (i < minX) minX = i; if (i > maxX) maxX = i;
                    if (j < minY) minY = j; if (j > maxY) maxY = j;
                }
            }
        }

        List<int[]> list = new ArrayList<>();
        if (!hasStone) {
            // 关键修复：空棋盘时，中心位置是 (7, 7)，在有效范围 0-13 内
            int center = Board.SIZE / 2; // 15/2 = 7
            list.add(new int[]{center, center});
            return list;
        }

        int pad = 2;
        // 已修复：允许在 0-14 的所有交叉点落子
        int sx = Math.max(0, minX - pad), ex = Math.min(Board.SIZE - 1, maxX + pad);
        int sy = Math.max(0, minY - pad), ey = Math.min(Board.SIZE - 1, maxY + pad);

        for (int x = sx; x <= ex; x++) {
            for (int y = sy; y <= ey; y++) {
                // 已移除：边界限制已移除，允许在 0-14 的所有交叉点落子
                if (!b.isEmpty(x, y)) continue;
                if (!hasNeighbor(b, x, y)) continue;
                list.add(new int[]{x, y});
            }
        }

        list.sort(Comparator.comparingInt(p ->
                - (localPotential(b, p[0], p[1], me)
                        + (int) (0.9 * localPotential(b, p[0], p[1], opp)))
        ));
        return list;
    }

    private boolean hasNeighbor(Board b, int x, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (b.inBounds(nx, ny) && b.get(nx, ny) != Board.EMPTY) return true;
            }
        }
        return false;
    }

    private int localPotential(Board b, int x, int y, char side) {
        b.place(x, y, side);
        int score = Evaluator.localPotential(b, x, y, side);
        b.place(x, y, Board.EMPTY);
        return score;
    }


}
