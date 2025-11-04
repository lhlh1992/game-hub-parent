package com.gamehub.gameservice.games.gomoku.domain.dto;

import lombok.Data;

/**
 * GameStateRecord
 * -------------------------------------------------------
 * 单盘棋局的权威状态（用于 Redis 持久化）。
 * - board 建议用 15x15=225 长度的紧凑字符串（'.','X','O'），
 *   也可用二维数组 JSON，但紧凑字符串更省空间/带宽。
 * -------------------------------------------------------
 */
@Data
public class GameStateRecord {
    /** 房间ID（冗余保存） */
    private String roomId;
    /** 棋局ID（UUID） */
    private String gameId;
    /** 当前是系列对局中的第几盘 */
    private int index;
    /** 15x15 棋盘紧凑字符串（'.'/'X'/'O'），长度应为 225 */
    private String board;
    /** 当前执子："X"/"O" */
    private String current;
    /** 上一步坐标，形如 "x,y"；若无则为 null/空字符串 */
    private String lastMove;
    /** 胜者："X"/"O"/"DRAW"/null（未结束） */
    private String winner;
    /** 是否终局 */
    private boolean over;
    /**步号/版本号 */
    private Integer step;


}
