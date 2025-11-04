package com.gamehub.gameservice.games.gomoku.domain.dto;

import lombok.Data;

/**
 * 房间基本信息 + Series 汇总信息
 * 持久化用的“房间元信息”数据模型（Redis JSON/Hash）。
 * 只包含可序列化字段，避免运行态对象（线程、AI 实例）。
 */
@Data
public class RoomMeta {
    /** 房间ID（冗余存储，便于调试与回填） */
    private String roomId;
    /** 当前局游戏ID */
    private String gameId;
    /** 对战模式：PVP / PVC */
    private String mode;
    /** 规则：STANDARD / RENJU */
    private String rule;
    /** AI 执子："X" / "O" / null（PVP 时为空） */
    private String aiPiece;

    /** 当前盘的 index（从 1 或 0 开始，按实际实现） */
    private int currentIndex;
    /** 黑方累计胜场数 */
    private int blackWins;
    /** 白方累计胜场数 */
    private int whiteWins;
    /** 平局数 */
    private int draws;

    /** 房主用户ID（创建房间的认证用户） */
    private String ownerUserId;
}
