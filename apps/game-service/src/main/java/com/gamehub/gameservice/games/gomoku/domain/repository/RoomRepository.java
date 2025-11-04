package com.gamehub.gameservice.games.gomoku.domain.repository;

import com.gamehub.gameservice.games.gomoku.domain.dto.RoomMeta;
import com.gamehub.gameservice.games.gomoku.domain.dto.SeatsBinding;
import com.gamehub.gameservice.games.gomoku.domain.model.SeriesView;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * RoomRepository
 * ----------------------------------------
 * 房间仓储接口（Room-level Repository）
 * - 定义房间信息及座位绑定在持久层的存取规范；
 * - 当前实现为 Redis，未来可扩展至 DB 或内存；
 * ----------------------------------------
 * Responsibilities:
 * 1. Save / Load / Delete room meta data (mode, rule, AI, wins, draws...)
 * 2. Save / Load / Delete seat bindings (session-seat mapping)
 * 3. Manage temporary seatKey (used for reconnecting or binding validation)
 */
public interface RoomRepository {

    /**
     * 保存房间元信息（含 mode、rule、series 统计等）
     * @param roomId 房间ID
     * @param meta   房间元数据对象
     * @param ttl    过期时间（可选，为空则永久）
     */
    void saveRoomMeta(String roomId, RoomMeta meta, Duration ttl);
    /**
     * 获取房间元信息
     * @param roomId 房间ID
     * @return 可选的 RoomMeta（若不存在返回 empty）
     */
    Optional<RoomMeta> getRoomMeta(String roomId);

    /**
     * 删除整个房间元信息
     * @param roomId 房间ID
     */
    void deleteRoom(String roomId);

    /**
     * 保存房间座位绑定信息（包括两个座位及 sessionId 绑定）
     * @param roomId 房间ID
     * @param seats  座位绑定对象
     * @param ttl    过期时间
     */
    void saveSeats(String roomId, SeatsBinding seats, Duration ttl);
    /**
     * 获取房间座位绑定信息
     * @param roomId 房间ID
     * @return 可选的 SeatsBinding
     */
    Optional<SeatsBinding> getSeats(String roomId);

    /**
     * 删除房间座位绑定信息
     * @param roomId 房间ID
     */
    void deleteSeats(String roomId);

    /**
     * 保存 seatKey -> 座位标识（X/O），带 TTL。
     * seatKey 用于断线重连后身份验证。
     * @param roomId   房间ID
     * @param seatKey  随机 seatKey
     * @param seatChar 座位标识 'X' / 'O'
     * @param ttl      过期时间
     * @return true 表示设置成功（SETNX 成功）
     */
    boolean setSeatKey(String roomId, String seatKey, String seatChar, Duration ttl);
    /**
     * 获取 seatKey 对应的座位标识
     * @param roomId  房间ID
     * @param seatKey seatKey
     * @return 'X' / 'O' 或 null
     */
    Character getSeatKey(String roomId, String seatKey);

    /**
     * 删除 seatKey
     * @param roomId  房间ID
     * @param seatKey seatKey
     */
    void deleteSeatKey(String roomId, String seatKey);


    // —— 系列统计（胜/负/和/局次） ——
    // === 系列比分统计 ===
    /** 胜负统计自增（终局后调用） */
    void incrSeriesOnFinish(String roomId, Character winner);

    /** 读取系列比分哈希（round / blackWins / whiteWins / draws） */
    Map<Object, Object> readSeriesHash(String roomId);

    /** 获取系列比分视图 */
    SeriesView getSeries(String roomId);

}
