package com.gamehub.gameservice.platform.ongoing;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记录某个用户正在进行中的游戏信息。
 * 目前仅支持 Gomoku，如需扩展可追加字段。
 */
@Data
@NoArgsConstructor
public class OngoingGameInfo {

    private String gameType;

    private String roomId;

    private String title;

    private long updatedAt;

    public static OngoingGameInfo gomoku(String roomId) {
        OngoingGameInfo info = new OngoingGameInfo();
        info.setGameType("gomoku");
        info.setRoomId(roomId);
        info.setTitle("五子棋对局中");
        info.setUpdatedAt(System.currentTimeMillis());
        return info;
    }
}



