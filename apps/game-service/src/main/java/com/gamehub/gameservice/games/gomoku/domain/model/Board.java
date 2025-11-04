package com.gamehub.gameservice.games.gomoku.domain.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Arrays;

/**
 * 五子棋棋盘：15x15 网格。
 * 约定：EMPTY='.', BLACK='X', WHITE='O'
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Board {
    /** 棋盘尺寸（15x15）。可调整为其他大小的常量。 */
    public static final int SIZE  = 15;
    /** 空位标记：'.' 表示该格未落子。 */
    public static final char EMPTY = '.';
    /** 黑子标记：'X' 表示玩家的棋子。 */
    public static final char BLACK = 'X';
    /** 白子标记：'O' 表示 AI 或对手的棋子。 */
    public static final char WHITE = 'O';

    /** 棋盘二维数组，存放当前局面状态。 */
    private final char[][] grid = new char[SIZE][SIZE];

    /**
     * 构造方法，初始化棋盘
     */
    public Board() {
        for (int i = 0; i < SIZE; i++) Arrays.fill(grid[i], EMPTY);
    }

    /** 是否在棋盘内 */
    public boolean inBounds(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    /** 读取该点的棋子，X or O */
    public char get(int x, int y) { return grid[x][y]; }

    /** 该点是否为空 */
    public boolean isEmpty(int x, int y) {
        return inBounds(x, y) && grid[x][y] == EMPTY;
    }

    /** 在(x,y)落子（不做合法性校验，由上层规则判定）
     *  不判断合法性（是否越界/是否已占），由规则层去做，职责单一。
     * */
    public void place(int x, int y, char piece) { grid[x][y] = piece; }

    /** 深拷贝棋盘（供状态复制/AI模拟使用） */
    public Board copy() {
        Board b = new Board();
        for (int i = 0; i < SIZE; i++) b.grid[i] = grid[i].clone();
        return b;
    }

    /** 返回一个只读视图副本（用于序列化给前端/日志） */
    public char[][] view() {
        char[][] v = new char[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++) v[i] = grid[i].clone();
        return v;
    }
}


