package com.gamehub.gameservice.games.gomoku.application;

import com.gamehub.gameservice.clock.scheduler.CountdownScheduler;
import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;
import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.model.Board;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuState;
import com.gamehub.gameservice.games.gomoku.domain.model.SeriesView;
import com.gamehub.gameservice.games.gomoku.domain.repository.GameStateRepository;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.GomokuMessages.BroadcastEvent;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * TurnClockCoordinator
 * -------------------------------------------------
 * 倒计时业务协调器（应用编排层）：将通用倒计时引擎与五子棋业务规则对接。
 *
 * 职责与边界：
 * 1) 在应用启动时（ApplicationReady）注册调度引擎的 tick 监听，并调用引擎进行全量恢复；
 *    - tick 回调：转译为房间内的 TICK 事件（left/side/deadlineEpochMs）并通过 WebSocket 广播；
 *    - restore：恢复所有未过期的倒计时；对已过期的锚点，引擎内部会先清理并通过 holder 锁回调一次超时；
 * 2) 每次对局状态变更后，由控制器调用 syncFromState 决定“本回合是否启动/续上/停止计时”：
 *    - 终局：停止计时；
 *    - PVE 且 aiTimed=false 且轮到 AI：停止计时；
 *    - 其他：计算 key/owner/version/deadline 并调用引擎 startOrResume；
 * 3) 当引擎回调 timeout 时，执行权威判负（gomokuService.resign），并广播 TIMEOUT/STATE/SNAPSHOT；
 *
 * 重要说明：
 * - 本类不管理线程池与 Redis，不参与通用超时判定（由 CountdownScheduler/Impl 负责）；
 * - 本类也不处理落子等输入请求（由 WebSocket 控制器负责）；
 * - 唯一职责：将“何时计时、如何通知、到期如何处理”的五子棋业务规则，编排到通用引擎的回调与调用中；
 * - 因此该类可安全归类为应用编排层（application），而非 controller/infrastructure。
 */
@Component
@Lazy
@RequiredArgsConstructor
public class TurnClockCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TurnClockCoordinator.class);

    // 通用倒计时调度器（不懂业务）
    private final CountdownScheduler scheduler;
    // 五子棋业务服务（判负/局面/座位等）
    private final @Lazy GomokuService gomokuService;
    // WebSocket 消息模板（推送到房间）
    private final SimpMessagingTemplate messaging;
    // 游戏状态仓储（用于保存状态到Redis）
    private final GameStateRepository gameStateRepository;

    @Value("${gomoku.turn.seconds:30}")
    // 单回合时长（秒）
    private int turnSeconds;

    @Value("${gomoku.turn.aiTimed:false}")
    // 是否给 AI 计时（PVE 时）
    private boolean aiTimed;

    /**
     * 应用启动后调用，注册倒计时引擎的 TICK 监听逻辑，并恢复所有未过期倒计时任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // 启动日志：开始注册 TICK 监听并准备恢复
        log.info("协调器启动：注册倒计时 TICK 监听并恢复活跃任务");
        // 注册每秒 TICK 的转发：把引擎回调转成房间内的 TICK 消息
        scheduler.setTickListener((key, owner, deadlineMs, left) -> {
            log.info("TICK 监听执行");
            // 从通用 key 中还原 roomId
            String roomId = extractRoomId(key);
            // 组装 WS 事件：TICK
            BroadcastEvent tick = new BroadcastEvent();
            tick.setRoomId(roomId);
            tick.setType("TICK");
            tick.setPayload(Map.of(
                    // 剩余秒
                    "left", (int) left,
                    // 被计时一方（"X"/"O"）
                    "side", owner,
                    // 绝对截止时间
                    "deadlineEpochMs", deadlineMs
            ));
            // 广播到 /topic/room.{roomId}
            messaging.convertAndSend(topic(roomId), tick);
        });

        // 启动后恢复未过期的倒计时（已过期的尝试触发一次超时）
        int restored = scheduler.restoreAllActive((key, owner, version) -> handleTimeout(key, owner));
        // 结束日志：恢复数量
        log.info("协调器启动完成：已恢复活跃倒计时任务 {} 个", restored);
    }

    /**
     * 根据最新对局状态驱动倒计时：终局停止；PVE 且不计 AI 则停；否则为当前应走方启动/续上本回合计时。
     * @param roomId 房间ID
     * @param state  最新的五子棋状态
     */
    public void syncFromState(String roomId, GomokuState state) {
        // 终局：停止计时
        if (state.over()) { stop(roomId); return; }
        // 是否人机模式
        boolean isPve = gomokuService.getMode(roomId) == Mode.PVE;
        // AI 的棋色
        char ai = gomokuService.getAiPiece(roomId);
        // 当前应走方
        char sideToMove = state.current();
        // 是否轮到 AI
        boolean aiTurn = isPve && (sideToMove == ai);
        // PVE 且不计 AI → 停止计时
        if (aiTurn && !aiTimed) { stop(roomId); return; }

        // 通用 key（前缀+roomId）
        String key = key(roomId);
        // 被计时一方
        String owner = String.valueOf(sideToMove);
        // 使用 gameId 作为回合版本
        String version = gomokuService.getGameId(roomId);
        // 本回合截止时间
        long deadline = System.currentTimeMillis() + turnSeconds * 1000L;
        // 启动/恢复倒计时；到期回调：转去判负/广播
        scheduler.startOrResume(key, owner, deadline, version,
                (k, o, v) -> handleTimeout(k, o));
    }

    // 对外暴露停止
    public void stop(String roomId) { scheduler.stop(key(roomId)); }

    /**
     * 处理回合超时：将超时方判负并广播 TIMEOUT、随后广播最新 STATE 与 SNAPSHOT。
     * @param key   调度键（含房间前缀）
     * @param owner 超时方（"X"/"O"）
     */
    private void handleTimeout(String key, String owner) {
        log.info("回合超时处理");
        // key → roomId
        String roomId = extractRoomId(key);
        // 所属棋色
        char side = (owner == null || owner.isEmpty()) ? 0 : owner.charAt(0);
        
        // 获取超时前的状态，用于CAS保存
        GomokuState before = gomokuService.getState(roomId);
        final String gameId = gomokuService.getGameId(roomId);
        int expectedStep = computeExpectedStep(before.board());
        char expectedTurn = before.current();
        
        // 权威判负
        GomokuState after = gomokuService.resign(roomId, side);
        
        // 保存超时后的状态到Redis
        GameStateRecord rec = buildRecord(after, roomId, gameId, expectedStep + 1);
        try {
            gameStateRepository.updateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, 0L);
        } catch (Exception e) {
            log.warn("保存超时状态到Redis失败: {}", e.getMessage());
        }

        // 广播 TIMEOUT
        BroadcastEvent timeout = new BroadcastEvent();
        timeout.setRoomId(roomId);
        timeout.setType("TIMEOUT");
        timeout.setPayload(java.util.Map.of("side", owner));
        messaging.convertAndSend(topic(roomId), timeout);

        // 取系列视图
        var sv = gomokuService.getSeries(roomId);
        // 广播 STATE
        BroadcastEvent stateEvt = new BroadcastEvent();
        stateEvt.setRoomId(roomId);
        stateEvt.setType("STATE");
        stateEvt.setPayload(new StatePayload(after, sv));
        messaging.convertAndSend(topic(roomId), stateEvt);

        // 广播 SNAPSHOT
        Object snap = gomokuService.snapshot(roomId);
        BroadcastEvent snapEvt = new BroadcastEvent();
        snapEvt.setRoomId(roomId);
        snapEvt.setType("SNAPSHOT");
        snapEvt.setPayload(snap);
        messaging.convertAndSend(topic(roomId), snapEvt);
    }
    
    /**
     * 计算当前棋盘上的棋子数量，作为CAS操作的期望值
     */
    private int computeExpectedStep(Board b) {
        int cnt = 0;
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                char p = b.get(x, y);
                if (p == 'X' || p == 'O') cnt++;
            }
        }
        return cnt;
    }
    
    /**
     * 从状态中构建一个GameStateRecord快照，以实现持久化
     */
    private GameStateRecord buildRecord(GomokuState s, String roomId, String gameId, int step) {
        GameStateRecord rec = new GameStateRecord();
        rec.setRoomId(roomId);
        rec.setGameId(gameId);
        rec.setOver(s.over());
        rec.setCurrent(String.valueOf(s.current()));
        if (s.over()) {
            rec.setWinner(s.winner() == null ? "DRAW" : String.valueOf(s.winner()));
        }
        rec.setStep(step);
        if (s.lastMove() != null) {
            rec.setLastMove(s.lastMove().x() + "," + s.lastMove().y());
        }
        // 构建棋盘字符串表示（'.'表示空位，'X'/'O'表示棋子）
        StringBuilder sb = new StringBuilder(225);
        Board b = s.board();
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                char p = b.get(x, y);
                sb.append((p == 'X' || p == 'O') ? p : '.');
            }
        }
        rec.setBoard(sb.toString());
        return rec;
    }

    // key 生成：通用前缀 + roomId
    private String key(String roomId) { return "gomoku:" + roomId; }
    // 反向解析 roomId
    private String extractRoomId(String key) {
        return key.startsWith("gomoku:") ? key.substring("gomoku:".length()) : key;
    }
    // WS 主题
    private String topic(String roomId) { return "/topic/room." + roomId; }

    /**
     * WS 载荷：封装当前盘状态与多盘视图，用于 STATE 事件推送。
     */
    public static class StatePayload {
        // 当前盘状态
        public final GomokuState state;
        // 多盘视图
        public final SeriesView series;
        public StatePayload(GomokuState s, SeriesView v) {
            this.state = s; this.series = v;
        }
    }
}


