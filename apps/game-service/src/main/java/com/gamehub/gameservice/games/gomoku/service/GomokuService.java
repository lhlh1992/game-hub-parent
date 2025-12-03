package com.gamehub.gameservice.games.gomoku.service;

import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuState;
import com.gamehub.gameservice.games.gomoku.domain.model.Move;
import com.gamehub.gameservice.games.gomoku.domain.model.SeriesView;

import java.util.Map;

public interface GomokuService {
    /** 新开房间；PVE 时 aiPiece 可 null（默认 O=后手），rule 可 null（默认 STANDARD） */
    String newRoom(Mode mode, Character aiPiece, Rule rule, String ownerUserId, String ownerName);

    GomokuState place(String roomId, int x, int y, char piece);

    /** 给定一方请求 AI 建议（不自动下） */
    Move suggest(String roomId, char side);

    /** 重开当前房间（保留配置：模式、规则、AI 方） */
    GomokuState restart(String roomId);


    /** 只读获取当前房间的棋局状态（用于 WS 新连接同步/调试） */
    GomokuState getState(String roomId);

    /**
     * ✅ 新增：一站式下子（必要时自动让 AI 走一步）
     * 语义：
     *   - 先执行玩家这一步；
     *   - 若房间模式=PVE 且这一步不是 AI 的棋色，则自动调用建议并为 AI 落子；
     *   - 返回“最终最新”的棋局状态（可能包含玩家一步 + AI 一步）。
     */
    GomokuState placeAndAutoIfNeeded(String roomId, int x, int y, char piece);


    /** 返回房间模式（PVE or PVP），用于控制器判断是否要触发 AI */
    Mode getMode(String roomId);

    /** 返回 AI 执子（仅 PVE 有效），用于控制器判断是否轮到 AI */
    char getAiPiece(String roomId);

    /** 返回房主用户ID（创建房间的用户） */
    String getOwnerUserId(String roomId);

    /**
     * 基于“调用方身份”（改为 userId）解析并（必要时）占一个座位，返回该调用方执子。
     * PVE：人类只能占“非AI”的那一边；已占则报 SEAT_TAKEN
     * PVP：尽量按 wantSide 分配，否则给另一边；两边都占满则 NOT_A_PLAYER
     */
    char resolveAndBindSide(String roomId, String userId, Character wantSide);

    /**
     * 返回当前盘的 gameId
     * @param roomId
     * @return
     */
    String  getGameId(String roomId);         // 返回当前盘的 gameId

    /**
     * 返回黑/白/和统计（一个只读视图）
     * @param roomId
     * @return
     */
    SeriesView getSeries(String roomId);         // 返回黑/白/和统计（一个只读视图）

    /**
     * 在同房间开新一盘（保留座位/规则/AI）
     * @param roomId
     * @return
     */
    GomokuState newGame(String roomId);           // 在同房间开新一盘（保留座位/规则/AI）

    /**
     * 认输：该 side 判负，结束本盘
     * @param roomId
     * @param side
     * @return
     */
    GomokuState resign(String roomId, char side); // 认输：该 side 判负，结束本盘


    /**
     *  =========== 页面刷新重入棋局逻辑 =================
     *  迁移友好：
     *   - 将来上用户体系：seatKey 可由 roomTicket/userToken 取代，上述 1/2 两个方法的实现内部切换数据源即可；接口保持不变。
     *   - 将来上 Redis/多节点：snapshot 的数据来源可以从内存切到 Redis（或 DB），接口同样保持不变。
     * */
    /**
     * 签发 seatKey
     * 为房间的某个座位签发一把 seatKey（强随机、仅在该房间生命周期内有效）。
     * @param roomId    房间ID
     * @param seat      'X' 或 'O'
     * @param userId    当前认证用户ID（原 sessionId）
     * @return seatKey  Base64URL/UUID 等强随机字符串
     */
    String issueSeatKey(String roomId, char seat, String userId, String seatKey);

    /**
     * 刷新/重连恢复 ===
     * 使用 seatKey 将当前用户绑定回房间中的原座位；失败返回 null。
     * @param roomId       房间ID
     * @param seatKey      之前签发给该座位的 seatKey（浏览器在刷新后携带）
     * @param userId       当前认证用户ID（刷新/重连后的调用者）
     * @return 'X' 或 'O'；若 seatKey 无效或不匹配该房间，则返回 null
     */
    Character bindBySeatKey(String roomId, String seatKey, String userId);

    /**
     * 房间的“只读快照”（拼装 FullSync 用）
     * 生成用于“刷新恢复首帧”的只读快照。
     * 注意：这里不要返回可变内部状态，避免被外部修改。
     */
    GomokuSnapshot snapshot(String roomId);

    /**
     * 玩家主动离开房间（手动点击退出）
     * @param roomId 房间ID
     * @param userId 当前用户ID
     * @return 离开结果（是否销毁房间、新房主、释放的座位）
     */
    LeaveResult leaveRoom(String roomId, String userId);

    record LeaveResult(boolean roomDestroyed, String newOwnerUserId, Character freedSeat) {}

    /**
     * 玩家准备/取消准备
     * @param roomId 房间ID
     * @param userId 当前用户ID
     * @return 新的准备状态（true=已准备，false=未准备）
     */
    boolean toggleReady(String roomId, String userId);

    /**
     * 获取玩家的准备状态
     * @param roomId 房间ID
     * @param userId 用户ID
     * @return 准备状态（true=已准备，false=未准备），如果用户不在房间则返回false
     */
    boolean getReadyStatus(String roomId, String userId);

    /**
     * 获取所有玩家的准备状态
     * @param roomId 房间ID
     * @return Map<userId, readyStatus>
     */
    Map<String, Boolean> getAllReadyStatus(String roomId);

    /**
     * 重置房间内所有玩家的准备状态为未准备
     * @param roomId 房间ID
     */
    void resetAllReady(String roomId);

    /**
     * 房主开始游戏（检查准备状态，切换房间状态从WAITING到PLAYING）
     * PVE模式：AI默认已准备，只需检查房主是否准备
     * PVP模式：需要所有真人玩家都准备
     * @param roomId 房间ID
     * @param userId 房主用户ID
     * @throws IllegalStateException 如果不是房主、房间状态不是WAITING、或玩家未全部准备
     */
    void startGame(String roomId, String userId);

    /**
     * 获取房间状态（WAITING/PLAYING/ENDED）
     * @param roomId 房间ID
     * @return 房间状态
     */
    com.gamehub.gameservice.games.gomoku.domain.enums.RoomPhase getRoomPhase(String roomId);

    /**
     * 设置房间状态
     * @param roomId 房间ID
     * @param phase 房间状态
     */
    void setRoomPhase(String roomId, com.gamehub.gameservice.games.gomoku.domain.enums.RoomPhase phase);

    /**
     * 判断用户是否已经在房间内（已绑定座位）
     * @param roomId 房间ID
     * @param userId 用户ID
     * @return true 表示用户已经在房间内
     */
    boolean isUserInRoom(String roomId, String userId);
}
