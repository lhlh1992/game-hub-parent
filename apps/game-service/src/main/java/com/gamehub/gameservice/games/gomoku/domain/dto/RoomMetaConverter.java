package com.gamehub.gameservice.games.gomoku.domain.dto;

import com.gamehub.gameservice.games.gomoku.domain.ai.GomokuAI;
import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.domain.model.Game;
import com.gamehub.gameservice.games.gomoku.domain.model.Room;
import lombok.Data;

/**
 * Room 与 RoomMeta 相互转换工具
 * 用于 Redis 持久化与服务重启恢复。
 */

public class RoomMetaConverter {

    /** 从 Room 转为 RoomMeta（用于保存） */
    public static RoomMeta toMeta(Room room) {
        RoomMeta meta = new RoomMeta();
        meta.setRoomId(room.getId());
        meta.setMode(room.getMode().name());
        meta.setRule(room.getRule().name());
        meta.setAiPiece(String.valueOf(room.getAiPiece()));
        meta.setGameId(room.getSeries().getCurrent().getGameId());
        meta.setCurrentIndex(room.getSeries().getNextIndex() - 1);
        meta.setBlackWins(room.getSeries().getBlackWins());
        meta.setWhiteWins(room.getSeries().getWhiteWins());
        meta.setDraws(room.getSeries().getDraws());
        return meta;
    }

    /** 从 RoomMeta 还原 Room（用于恢复） */
    public static Room toRoom(RoomMeta meta) {
        Mode mode = Mode.valueOf(meta.getMode());
        Rule rule = Rule.valueOf(meta.getRule());
        char aiPiece = meta.getAiPiece() != null && !meta.getAiPiece().isBlank()
                ? meta.getAiPiece().charAt(0) : ' ';
        GomokuAI ai = new GomokuAI(3, rule == Rule.RENJU);

        Room room = new Room(meta.getRoomId(), mode, rule, aiPiece, ai,meta.getGameId());
        Room.Series s = room.getSeries();
        s.setBlackWins(meta.getBlackWins());
        s.setWhiteWins(meta.getWhiteWins());
        s.setDraws(meta.getDraws());
        s.setNextIndex(meta.getCurrentIndex() + 1);
        s.setCurrent(new Game(meta.getCurrentIndex(),meta.getGameId()));

        return room;
    }
}
