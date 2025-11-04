package com.gamehub.gameservice.games.gomoku.domain.rule;

import com.gamehub.gameservice.games.gomoku.domain.model.Board;

/**
 * 禁手判定
 * 这是一个实用版禁手检测：
 * -长连：成线长度 ≥ 6。
 * -四四：同时形成 ≥2 个“四”（活四或冲四）。
 * -三三：同时形成 ≥2 个“活三”。
 */
public class GomokuJudgeRenju {

    private static final int[][] DIRS = {{1,0},{0,1},{1,1},{1,-1}};

    /** 黑方禁手：长连/四四/三三；仅当该点为空才判断 */
    public static boolean isForbiddenMove(Board b, int x, int y) {
        if (!b.isEmpty(x,y)) return true; // 已占视作非法
        b.place(x,y, Board.BLACK);
        boolean overline = isOverline(b, x, y, Board.BLACK);  //黑方长连超过5个
        int fours = countFours(b, x, y, Board.BLACK);         // 活四+冲四
        int openThrees = countOpenThrees(b, x, y, Board.BLACK);// 活三
        b.place(x,y, Board.EMPTY);
        return overline || fours >= 2 || openThrees >= 2;
    }

    // —— 实用版规则组件 ——
    private static boolean isOverline(Board b,int x,int y,char p){
        for (int[] d:DIRS) {
            int cnt = 1 + count(b,x,y,d[0],d[1],p) + count(b,x,y,-d[0],-d[1],p);
            if (cnt >= 6) return true;
        }
        return false;
    }
    private static int countFours(Board b,int x,int y,char p){
        int total=0;
        for (int[] d:DIRS) {
            //方向连棋数量
            int c = 1 + count(b,x,y,d[0],d[1],p) + count(b,x,y,-d[0],-d[1],p);
            //落子点出发，两端尽头是否为空
            int open = openEnds(b,x,y,d[0],d[1],p);
            if (c==4 && open>=1) total++;       // 连四
            if (c==5 && open==1) total++;       // 冲四
        }
        return total;
    }
    private static int countOpenThrees(Board b,int x,int y,char p){
        int total=0;
        for (int[] d:DIRS) {
            int c = 1 + count(b,x,y,d[0],d[1],p) + count(b,x,y,-d[0],-d[1],p);
            int open = openEnds(b,x,y,d[0],d[1],p);
            if (c==3 && open==2 && canExtendToFour(b,x,y,d[0],d[1],p)) total++;
        }
        return total;
    }
    /**
     * 沿某个方向数连续相同棋子（不含起点），直到越界或遇到不同棋子停止
     * @param b
     * @param x
     * @param y
     * @param dx   x偏移量
     * @param dy   y偏移量
     * @param p
     * @return
     */
    private static int count(Board b,int x,int y,int dx,int dy,char p){
        int c=0; x+=dx; y+=dy;
        while (b.inBounds(x,y) && b.get(x,y)==p) { c++; x+=dx; y+=dy; }
        return c;
    }
    /**
     * 从落子点沿指定方向检查这串棋子的两端是否为空。
     * 返回 0~2：两头空=2，一头空=1，两头堵=0。
     */
    private static int openEnds(Board b,int x,int y,int dx,int dy,char p){
        int open=0, cx=x+dx, cy=y+dy;
        while (b.inBounds(cx,cy) && b.get(cx,cy)==p) { cx+=dx; cy+=dy; }
        if (b.inBounds(cx,cy) && b.get(cx,cy)==Board.EMPTY) open++;
        cx=x-dx; cy=y-dy;
        while (b.inBounds(cx,cy) && b.get(cx,cy)==p) { cx-=dx; cy-=dy; }
        if (b.inBounds(cx,cy) && b.get(cx,cy)==Board.EMPTY) open++;
        return open;
    }
    private static boolean canExtendToFour(Board b,int x,int y,int dx,int dy,char p){
        int cx=x, cy=y;
        while (b.inBounds(cx+dx,cy+dy) && b.get(cx+dx,cy+dy)==p) { cx+=dx; cy+=dy; }
        if (b.inBounds(cx+dx,cy+dy) && b.get(cx+dx,cy+dy)==Board.EMPTY) {
            b.place(cx+dx,cy+dy,p); boolean ok = lineCount(b,x,y,dx,dy,p)>=4; b.place(cx+dx,cy+dy,Board.EMPTY);
            if (ok) return true;
        }
        cx=x; cy=y;
        while (b.inBounds(cx-dx,cy-dy) && b.get(cx-dx,cy-dy)==p) { cx-=dx; cy-=dy; }
        if (b.inBounds(cx-dx,cy-dy) && b.get(cx-dx,cy-dy)==Board.EMPTY) {
            b.place(cx-dx,cy-dy,p); boolean ok = lineCount(b,x,y,dx,dy,p)>=4; b.place(cx-dx,cy-dy,Board.EMPTY);
            if (ok) return true;
        }
        return false;
    }
    private static int lineCount(Board b,int x,int y,int dx,int dy,char p){
        return 1 + count(b,x,y,dx,dy,p) + count(b,x,y,-dx,-dy,p);
    }
}
