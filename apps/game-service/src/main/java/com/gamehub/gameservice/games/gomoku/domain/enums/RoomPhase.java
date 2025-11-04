package com.gamehub.gameservice.games.gomoku.domain.enums;

public enum RoomPhase {

    WAITING,   // 等待开始（不能落子）
    PLAYING,   // 对局中（允许落子）
    ENDED      // 已结束（可由房主重开回 WAITING）
}
