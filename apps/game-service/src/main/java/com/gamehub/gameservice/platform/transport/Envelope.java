package com.gamehub.gameservice.platform.transport;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 传输消息外壳（平台通用，跨所有游戏复用）
 * - 强类型泛型载荷：Envelope<T>
 * - 最少字段：kind / game / roomId / payload / ts / seq
 * - 提供静态工厂：state/event/error/of
 *
 * 用法示例：
 *   Envelope<GomokuState> msg = Envelope.state("gomoku", roomId, state);
 *   Envelope<ErrorDTO>    err = Envelope.error("gomoku", roomId, "ILLEGAL_MOVE", "禁手");
 */
public final class Envelope<T> implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /** 消息类别（语义层）：STATE=完整状态，EVENT=增量事件，ERROR=错误通知 */
    public enum Kind { STATE, EVENT, ERROR }

    private final Kind kind;
    private final String game;     // gomoku / chess / poker ...
    private final String roomId;
    private final T payload;
    private final long ts;         // 服务器时间戳（ms）
    private final long seq;        // 可选：房间内递增序号，没有就传 0

    private Envelope(Kind kind, String game, String roomId, T payload, long ts, long seq) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.game = Objects.requireNonNull(game, "game");
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        this.payload = payload;
        this.ts = ts;
        this.seq = seq;
    }

    /** 自由构造（若不关心 seq，传 0） */
    public static <T> Envelope<T> of(Kind kind, String game, String roomId, T payload, long seq) {
        return new Envelope<>(kind, game, roomId, payload, Instant.now().toEpochMilli(), seq);
    }

    /** 完整状态广播 */
    public static <T> Envelope<T> state(String game, String roomId, T payload) {
        return of(Kind.STATE, game, roomId, payload, 0);
    }

    /** 增量事件（如一步落子、聊天等） */
    public static <T> Envelope<T> event(String game, String roomId, T payload) {
        return of(Kind.EVENT, game, roomId, payload, 0);
    }

    /** 错误通知（payload 建议用 ErrorDTO） */
    public static <T> Envelope<T> error(String game, String roomId, T payload) {
        return of(Kind.ERROR, game, roomId, payload, 0);
    }

    /** 方便在保留头部的情况下，替换载荷类型 */
    public <U> Envelope<U> withPayload(U newPayload) {
        return new Envelope<>(this.kind, this.game, this.roomId, newPayload, this.ts, this.seq);
    }

    // —— Getters ——（保持不可变对象，无 setters）
    public Kind kind()   { return kind; }
    public String game() { return game; }
    public String roomId() { return roomId; }
    public T payload()   { return payload; }
    public long ts()     { return ts; }
    public long seq()    { return seq; }

    @Override public String toString() {
        return "Envelope{" +
                "kind=" + kind +
                ", game='" + game + '\'' +
                ", roomId='" + roomId + '\'' +
                ", ts=" + ts +
                ", seq=" + seq +
                '}';
    }
}
