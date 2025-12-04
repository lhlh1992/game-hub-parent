package com.gamehub.gameservice.games.gomoku.domain.model;

import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;
import com.gamehub.gameservice.games.gomoku.domain.dto.RoomMeta;
import com.gamehub.gameservice.games.gomoku.domain.dto.SeatsBinding;
import com.gamehub.gameservice.games.gomoku.domain.dto.TurnAnchor;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 聚合后的五子棋房间视图（服务内部用，只读）。
 *
 * <p>把 Redis 里分散存储的几块数据聚合在一起，避免在 service 里到处传
 * {@link RoomMeta} / {@link SeatsBinding} / {@link GameStateRecord} / {@link TurnAnchor} 四个对象。</p>
 *
 * <p>注意：这是服务内部使用的“聚合视图”，不是直接对外返回的 DTO。</p>
 */
@Getter
@AllArgsConstructor
public class RoomView {

    /** 房间元信息（mode / rule / owner / currentIndex / gameId 等） */
    private final RoomMeta meta;

    /** 座位绑定信息（X/O 当前绑定的 sessionId、观战人数、readyByUserId 等） */
    private final SeatsBinding seats;

    /** 当前局面快照（棋盘串、当前行棋方、winner、是否结束等） */
    private final GameStateRecord game;

    /** 回合计时锚点（side、deadlineEpochMs、turnSeq）。可能为 null。 */
    private final TurnAnchor turnAnchor;

    /** X 座是否已被占用（根据 seats 预先计算，方便调用方直接使用） */
    private final boolean seatXOccupied;

    /** O 座是否已被占用（根据 seats 预先计算，方便调用方直接使用） */
    private final boolean seatOOccupied;

    /**
     * 当前应走方：'X' / 'O' / null。
     * <p>优先来自 {@link TurnAnchor}，如果没有锚点则从 {@link GameStateRecord#getCurrent()} 推导。</p>
     */
    private final Character sideToMove;

    /**
     * 本回合的截止时间戳（ms），可能为 null（未开启计时）。
     * <p>来自 {@link TurnAnchor#getDeadlineEpochMs()}。</p>
     */
    private final Long deadlineEpochMs;
}