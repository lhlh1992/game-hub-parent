package com.gamehub.gameservice.games.gomoku.domain.model;

/**
 * 五子棋房间的只读快照（用于 FullSync）
 * —— 刷新后客户端一次性同步全部状态。
 *
 * 注意：
 * - 不暴露任何可变引用。
 * - 所有字段为 final，不可更改。
 * - 未来可从内存切换到 Redis/数据库实现，调用方不受影响。
 */
public final class GomokuSnapshot {
    /** 房间唯一 ID（与 Room.id 对应） */
    public final String roomId;
    /** 黑棋座位是否已被占用（true = 有玩家） */
    public final boolean seatXOccupied;
    /** 白棋座位是否已被占用（true = 有玩家或 AI） */
    public final boolean seatOOccupied;
    /** 对战模式：PVP（玩家对玩家）或 PVE（玩家对 AI） */
    public final String mode;        // PVP | PVC
    /** 当模式为 PVE 时，AI 执子的棋子方：'X'（先手）或 'O'（后手）；PVP 模式下为 null */
    public final Character aiSide;   // PVC 时 'X'/'O'；PVP 为 null
    /** 规则类型：STANDARD（普通）或 RENJU（连珠禁手） */
    public final String rule;        // STANDARD | RENJU

    /** 房间状态：WAITING / PLAYING / ENDED */
    public final String phase;

    /** 棋盘尺寸（一般为 15） */
    public final int boardSize;

    /** 棋盘格局快照（二维字符数组：'X'、'O'、'.'） */
    public final char[][] cells;

    /** 当前轮到哪一方执子：'X' 或 'O'；若对局结束则为 null */
    public final Character sideToMove;
    /** 当前回合序号（从 1 开始，换手或重开自增） */
    public final long turnSeq;
    /** 当前回合的绝对截止时间（UTC 毫秒时间戳，用于回合倒计时） */
    public final Long deadlineEpochMs;

    /** 当前局所在的回合轮次（Series round，用于系列赛计数） */
    public final int round;
    /** 黑棋累计得分（Series 统计项） */
    public final int scoreX;
    /** 白棋累计得分（Series 统计项） */
    public final int scoreO;

    /** 当前对局结果：X_WIN、O_WIN、DRAW，未结束则为 null */
    public final String outcome;     // X_WIN | O_WIN | DRAW | null

    /** 玩家准备状态快照：userId -> true(已准备)/false(未准备) */
    public final java.util.Map<String, Boolean> readyStatus;

    /**
     * 构造函数：生成一个完整的不可变快照。
     */
    public GomokuSnapshot(String roomId,
                          boolean seatXOccupied,
                          boolean seatOOccupied,
                          String mode,
                          Character aiSide,
                          String rule,
                          String phase,
                          int boardSize,
                          char[][] cells,
                          Character sideToMove,
                          long turnSeq,
                          Long deadlineEpochMs,
                          int round,
                          int scoreX,
                          int scoreO,
                          String outcome,
                          java.util.Map<String, Boolean> readyStatus) {
        this.roomId = roomId;
        this.seatXOccupied = seatXOccupied;
        this.seatOOccupied = seatOOccupied;
        this.mode = mode;
        this.aiSide = aiSide;
        this.rule = rule;
        this.phase = phase;
        this.boardSize = boardSize;
        this.cells = cells;
        this.sideToMove = sideToMove;
        this.turnSeq = turnSeq;
        this.deadlineEpochMs = deadlineEpochMs;
        this.round = round;
        this.scoreX = scoreX;
        this.scoreO = scoreO;
        this.outcome = outcome;
        // 防御式拷贝，避免外部修改
        this.readyStatus = (readyStatus == null)
                ? java.util.Collections.emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(readyStatus));
    }
}
