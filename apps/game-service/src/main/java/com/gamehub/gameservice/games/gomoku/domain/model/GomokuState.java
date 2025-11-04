package com.gamehub.gameservice.games.gomoku.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamehub.gameservice.engine.core.GameState;
import lombok.Data;

/**
 * 对局状态操作。
 * 作用：整盘对局的“单一事实来源”（棋盘、轮到谁、是否结束、赢家、上一手）。
 * - 持有棋盘 Board
 * - 记录当前执子方 current
 * - 记录是否结束/赢家
 * - 记录上一手 lastMove（便于胜负扫描/回放）
 * 设计说明
 * -GomokuState 不做规则校验，只做状态变更（apply）——规则单独放在 rule 包。
 * -current 只保存轮到谁，避免到处判断；lastMove 方便胜负扫描和回放重演。
 * -copy() 走深拷贝逻辑，确保 AI 搜索时不会污染实盘。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL) // null 字段不输出（可选）
public class GomokuState implements GameState {
    /**
     * 棋盘定义为不可变对象
     */
    private final Board board = new Board();

    /** 当前轮到谁：'X'（黑先）或 'O' */
    private char current = Board.BLACK;

    /** 对局是否结束 */
    private boolean over = false;

    /** 赢家：'X' 或 'O'；未结束为 null；和棋可按需要扩展为 'D' 或独立标志 */
    private Character winner = null;

    /** 记录上一手，便于快速胜负判定与回放 */
    private Move lastMove;

    // --------- 读方法（暴露给外部） ----------
    public Board board() { return board; }
    public char current() { return current; }
    public boolean over() { return over; }
    public Character winner() { return winner; }
    public Move lastMove() { return lastMove; }

    // --------- 状态变更（由上层用例调用） ----------
    /** 应用一次合法的落子（不做合法性判断，由规则层确保合法） */
    public void apply(Move m) {
        board.place(m.x(), m.y(), m.piece());
        lastMove = m;
        // 切换执子方
        current = (current == Board.BLACK) ? Board.WHITE : Board.BLACK;
    }

    /** 结束对局并设置赢家 */
    public void setOver(char winnerPiece) {
        this.over = true;
        this.winner = winnerPiece;
    }

    /** 深拷贝：复制棋盘与对局元信息 */
    @Override
    public GomokuState copy() {
        GomokuState s = new GomokuState();
        // 深拷贝棋盘
        Board b = this.board;
        for (int i = 0; i < Board.SIZE; i++) {
            for (int j = 0; j < Board.SIZE; j++) {
                char p = b.get(i, j);
                if (p != Board.EMPTY) s.board.place(i, j, p);
            }
        }
        // 拷贝元信息
        s.current = this.current;
        s.over = this.over;
        s.winner = this.winner;
        s.lastMove = this.lastMove; // Move 是不可变 record，引用即可
        return s;
    }
}
