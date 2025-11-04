package com.gamehub.gameservice.games.gomoku.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 房间对局信息（Series）概要信息视图：
 * 用于前端显示比分、第几盘、当前局 ID。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeriesView {
    /** 当前第几盘（从 1 开始） */
    private int index;

    /** 当前局唯一ID（gameId） */
    private String gameId;

    /** 黑方胜场 */
    private int blackWins;

    /** 白方胜场 */
    private int whiteWins;

    /** 平局数 */
    private int draws;

    /** 当前局是否结束 */
    private boolean over;

    /** 当前局胜者（可能为空） */
    private Character winner;

}
