package com.gamehub.gameservice.games.gomoku.interfaces.ws;
import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;
import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuState;
import com.gamehub.gameservice.games.gomoku.domain.model.Move;
import com.gamehub.gameservice.games.gomoku.domain.model.SeriesView;
import com.gamehub.gameservice.games.gomoku.domain.repository.GameStateRepository;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.GomokuMessages.*;
import com.gamehub.gameservice.games.gomoku.domain.constants.GameMessages;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import com.gamehub.gameservice.games.gomoku.domain.model.Board;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.Map;
import com.gamehub.gameservice.games.gomoku.application.TurnClockCoordinator;
import org.apache.commons.lang3.StringUtils;

/**
 * Gomoku WebSocket 控制器
 * ----------------------------------------
 * 负责接收前端通过 STOMP 发送的指令（如 /app/gomoku.place），
 * 并通过 SimpMessagingTemplate 将对局状态或错误消息广播给所有订阅者。
 *
 * 当前控制器实现了：
 *   1. 玩家落子 → 立刻推送“玩家局面”
 *   2. 若房间为 PVE 且轮到 AI → 延迟 2 秒推送“AI 局面”
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class GomokuWsController {
    /** 游戏逻辑服务层（包含房间、棋盘状态、AI） */
    private final GomokuService gomokuService;
    /** Spring 的消息模板，用于广播到 /topic/... */
    private final SimpMessagingTemplate messaging;

    /** 单据游戏状态 */
    private final GameStateRepository gameStateRepository;

    // 顶部字段（与 TurnClockManager 同源）
    @Value("${gomoku.turn.seconds:30}")
    private int turnSeconds;
    /**
     * 简单定时器：用于“AI 延迟 2 秒”再走
     */
    private final ScheduledExecutorService aiScheduler;

    /**
     * 防抖：同一房间只保留一个待执行的 AI 任务（多人/多端安全）
     */
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingAi = new ConcurrentHashMap<>();

    /**
     * 注入时钟组件
     */
    private final TurnClockCoordinator coordinator;

    /**
     * 处理客户端的“落子指令”。
     * ----------------------------------------
     * 路径：/app/gomoku.place
     * 流程：
     *   1. 调用 GomokuService.place() 落子
     *   2. 推送当前棋盘状态
     *   3. 若房间是 PVE 且轮到 AI → 延迟 2 秒再调用 AI 并推送
     */
    @MessageMapping("/gomoku.place")
    public void place(PlaceCmd cmd, SimpMessageHeaderAccessor sha) {
        // 获取房间ID，用于标识游戏房间
        final String roomId = cmd.getRoomId();
        // 获取当前游戏ID，用于防止AI跨盘操作（如果游戏重新开始，AI任务会被取消）
        final String gameIdAtSchedule = gomokuService.getGameId(roomId); // 防AI跨盘
        // 获取认证用户（通过网关/OIDC），以 userId 作为稳定身份
        final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();

        try {
            // PVE模式权限检查：只有房主可以走棋
            Mode mode = gomokuService.getMode(roomId);
            if (mode == Mode.PVE) {
                String ownerUserId = gomokuService.getOwnerUserId(roomId);
                if (!userId.equals(ownerUserId)) {
                    throw new IllegalStateException("PVE模式下，只有房主可以走棋");
                }
            }

            // 身份绑定与座位分配
            bindSeatIfProvided(roomId, cmd.getSeatKey(), userId);
            Character want = (cmd.getSide() == Board.BLACK || cmd.getSide() == Board.WHITE) ? cmd.getSide() : null;
            char caller = gomokuService.resolveAndBindSide(roomId, userId, want);
            issueSeatKeyIfNeeded(roomId, caller, userId, cmd.getSeatKey());

            // 落子前状态与 CAS 期望
            GomokuState before = gomokuService.getState(roomId);
            // 计算当前棋盘上的棋子数量，作为CAS操作的期望值
            int expectedStep = computeExpectedStep(before.board());
            // 获取当前应该轮到谁下棋（'X' 或 'O'）
            char expectedTurn = before.current();

            // 玩家落子
            GomokuState state = gomokuService.place(roomId, cmd.getX(), cmd.getY(), caller);

            // 构造棋盘快照并落库（原子 CAS）
            long nextDeadlineMillis = state.over() ? 0L : System.currentTimeMillis() + turnSeconds * 1000L;
            GameStateRecord rec = buildRecord(state, roomId, gameIdAtSchedule, expectedStep + 1);
            //
            persistStateAtomically(roomId, gameIdAtSchedule, expectedStep, expectedTurn, rec, nextDeadlineMillis);

            // 广播当前局面
            sendState(roomId, state);

            // PVE AI 处理（延迟 1~1.5s）
            maybeScheduleAi(roomId, state, gameIdAtSchedule);
        } catch (Exception e) {
            sendError(roomId, e.getMessage());
        }
    }



    // 认输（需要是房间内的玩家）
    @MessageMapping("/gomoku.resign")
    public void resign(SimpleCmd cmd,
                       SimpMessageHeaderAccessor sha) {
        final String roomId = cmd.getRoomId();
        final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();
        try {
            // PVE模式权限检查：只有房主可以认输
            Mode mode = gomokuService.getMode(roomId);
            if (mode == Mode.PVE) {
                String ownerUserId = gomokuService.getOwnerUserId(roomId);
                if (!userId.equals(ownerUserId)) {
                    throw new IllegalStateException("PVE模式下，只有房主可以认输");
                }
            }

            if (cmd.getSeatKey() != null && !cmd.getSeatKey().isBlank()) {
                gomokuService.bindBySeatKey(roomId, cmd.getSeatKey(), userId);
            }
            // 权限校验：必须是房间内的玩家才能认输
            char side = gomokuService.resolveAndBindSide(roomId, userId, null);
            
            // 获取认输前的状态，用于CAS保存
            GomokuState before = gomokuService.getState(roomId);
            final String gameId = gomokuService.getGameId(roomId);
            int expectedStep = computeExpectedStep(before.board());
            char expectedTurn = before.current();
            
            // 执行认输
            GomokuState s = gomokuService.resign(roomId, side);
            cancelAi(roomId);
            
            // 保存认输后的状态到Redis
            GameStateRecord rec = buildRecord(s, roomId, gameId, expectedStep + 1);
            persistStateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, 0L);
            
            sendState(roomId, s); // ★ sendState 会再发 SNAPSHOT
        } catch (IllegalStateException e) {
            // 房间已满或不是玩家，拒绝操作
            sendError(roomId, "权限不足：只有房间内的玩家才能认输");
        } catch (Exception e) {
            sendError(roomId, e.getMessage());
        }
    }

    // 准备/取消准备
    @MessageMapping("/gomoku.ready")
    public void ready(SimpleCmd cmd, SimpMessageHeaderAccessor sha) {
        final String roomId = cmd.getRoomId();
        final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();
        try {
            if (cmd.getSeatKey() != null && !cmd.getSeatKey().isBlank()) {
                gomokuService.bindBySeatKey(roomId, cmd.getSeatKey(), userId);
            }
            
            // 切换准备状态
            boolean newReady = gomokuService.toggleReady(roomId, userId);
            
            // 统一广播房间全貌快照（包含更新后的准备状态）
            broadcastSnapshot(roomId);
        } catch (Exception e) {
            sendError(roomId, e.getMessage());
        }
    }

    // 开始游戏（只有房主可以调用）
    @MessageMapping("/gomoku.start")
    public void startGame(SimpleCmd cmd, SimpMessageHeaderAccessor sha) {
        final String roomId = cmd.getRoomId();
        final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();
        try {
            if (cmd.getSeatKey() != null && !cmd.getSeatKey().isBlank()) {
                gomokuService.bindBySeatKey(roomId, cmd.getSeatKey(), userId);
            }
            
            // 开始游戏（会检查房主权限和准备状态，并切换 phase: WAITING -> PLAYING）
            gomokuService.startGame(roomId, userId);
            
            // 统一广播房间全貌快照（包含更新后的 phase、棋盘状态、比分等）
            broadcastSnapshot(roomId);
            
            // 同时发送当前游戏状态（STATE 事件，用于增量更新棋盘）
            GomokuState state = gomokuService.getState(roomId);
            sendState(roomId, state);
        } catch (Exception e) {
            sendError(roomId, e.getMessage());
        }
    }

    // 再来一局（需要是房间内的玩家）
    @MessageMapping("/gomoku.restart")
    public void restart(SimpleCmd cmd,SimpMessageHeaderAccessor sha) {
        final String roomId = cmd.getRoomId();
        final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();

        try {
            // PVE模式权限检查：只有房主可以重开
            Mode mode = gomokuService.getMode(roomId);
            if (mode == Mode.PVE) {
                String ownerUserId = gomokuService.getOwnerUserId(roomId);
                if (!userId.equals(ownerUserId)) {
                    throw new IllegalStateException("PVE模式下，只有房主可以重开");
                }
            }

            if (cmd.getSeatKey() != null && !cmd.getSeatKey().isBlank()) {
                gomokuService.bindBySeatKey(roomId, cmd.getSeatKey(), userId);
            }
            // 权限校验：必须是房间内的玩家才能重开
            char side = gomokuService.resolveAndBindSide(roomId, userId, null);
            cancelAi(roomId);
            coordinator.stop(roomId);
            GomokuState s = gomokuService.newGame(roomId);
            sendState(roomId, s);  // ★ sendState 会再发 SNAPSHOT
        } catch (IllegalStateException e) {
            // 房间已满或不是玩家，拒绝操作
            sendError(roomId, "权限不足：只有房间内的玩家才能重开");
        } catch (Exception e) {
            sendError(roomId, e.getMessage());
        }
    }

    /**
     * 房主踢出玩家
     * 路径：/app/gomoku.kick
     */
    @MessageMapping("/gomoku.kick")
    public void kickPlayer(KickCmd cmd, SimpMessageHeaderAccessor sha) {
        final String roomId = cmd.getRoomId();
        final String userId = Objects.requireNonNull(sha.getUser(), "user is null").getName();
        final String targetUserId = cmd.getTargetUserId();

        try {
            // 身份绑定
            bindSeatIfProvided(roomId, cmd.getSeatKey(), userId);

            // 先向被踢玩家发送踢人事件（在断开连接之前发送，确保能收到）
            BroadcastEvent kickEvent = new BroadcastEvent();
            kickEvent.setRoomId(roomId);
            kickEvent.setGameId(gomokuService.getGameId(roomId));
            kickEvent.setType("KICKED");
            kickEvent.setPayload(Map.of("reason", GameMessages.KICKED_OUT_REASON));
            messaging.convertAndSendToUser(targetUserId, "/queue/gomoku.kicked", kickEvent);
            
            // 等待一小段时间，确保消息发送完成
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 调用Service踢人（会断开连接）
            GomokuService.KickResult result = gomokuService.kickPlayer(roomId, userId, targetUserId);

            if (result.success()) {
                // 广播SNAPSHOT给房间内剩余玩家
                broadcastSnapshot(roomId);
            } else {
                sendError(roomId, result.reason());
            }
        } catch (IllegalStateException e) {
            sendError(roomId, e.getMessage());
        } catch (Exception e) {
            log.error("踢人失败: roomId={}, targetUserId={}", roomId, targetUserId, e);
            sendError(roomId, GameMessages.KICK_FAILED + "，请稍后再试");
        }
    }


    private void cancelAi(String roomId) {
        ScheduledFuture<?> old = pendingAi.remove(roomId);
        if (old != null) old.cancel(false);
    }

    /**
     * 如果提供了seatKey，则将当前会话绑定到此房间的该座位。
     */
    private void bindSeatIfProvided(String roomId, String seatKey, String userId) {
        if (seatKey != null && !seatKey.isBlank()) {
            gomokuService.bindBySeatKey(roomId, seatKey, userId);
        }
    }

    /**
     * 在用户首次就座时，为其发放一个座位号，并在创建后将其推送给客户端
     */
    private void issueSeatKeyIfNeeded(String roomId, char caller, String userId, String providedSeatKey) {
        try {
            // 若未提供则生成；若已提供则重复使用
            String seatKey = gomokuService.issueSeatKey(roomId, caller, userId, providedSeatKey);
            if (StringUtils.isNotBlank(seatKey)) {
                // 点对点发送给当前用户（基于认证主体名）
                messaging.convertAndSendToUser(userId, "/queue/gomoku.seat",
                        new SeatGranted(seatKey, caller));
            }
        } catch (Exception ignore) {}
    }

    /**
     * 计算棋盘上的棋子数量，以进行预期的CAS步骤。
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
     * 从状态中构建一个GameStateRecord快照，以实现持久化/广播。
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
        //  构建棋盘字符串表示（'.'表示空位，'X'/'O'表示棋子）
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

    /**
     * 使用CAS：预期步骤/预期转向必须匹配
     */
    private void persistStateAtomically(String roomId,
                                        String gameId,
                                        int expectedStep,
                                        char expectedTurn,
                                        GameStateRecord rec,
                                        long nextDeadlineMillis) {
        try {
            gameStateRepository.updateAtomically(roomId, gameId, expectedStep, expectedTurn, rec, nextDeadlineMillis);
        } catch (Exception ignore) {}
    }

    /**
     * 如果是PVE且轮到AI行动，则以较小延迟安排AI移动。
     */
    private void maybeScheduleAi(String roomId, GomokuState state, String gameIdAtSchedule) {
        if (state.over() || gomokuService.getMode(roomId) != Mode.PVE) return;
        if (state.current() != gomokuService.getAiPiece(roomId)) return;

        // 取消该房间之前的AI任务（防止重复执行）
        ScheduledFuture<?> old = pendingAi.remove(roomId);
        if (old != null) old.cancel(false);
        // 获取AI的棋子类型
        final char ai = gomokuService.getAiPiece(roomId);
        // 设置AI延迟时间：1-1.5秒随机延迟，模拟人类思考时间
        long delay = 1000 + ThreadLocalRandom.current().nextLong(501);
        ScheduledFuture<?> fut = aiScheduler.schedule(() -> runAiTurn(roomId, gameIdAtSchedule, ai), delay, TimeUnit.MILLISECONDS);
        // 将新的AI任务保存到待执行队列
        pendingAi.put(roomId, fut);
    }

    /**
     *  AI任务体：验证gameId，建议并放置，CAS持久化，然后广播。
     */
    private void runAiTurn(String roomId, String gameIdAtSchedule, char ai) {
        try {
            // 检查游戏是否还在进行（防止跨盘操作）
            if (!gameIdAtSchedule.equals(gomokuService.getGameId(roomId))) return;
            // 获取AI执行时的当前游戏状态
            GomokuState now = gomokuService.getState(roomId);
            if (now.over()) return;

            // 获取AI建议的落子位置
            Move mv = gomokuService.suggest(roomId, ai);
            if (mv == null) return;

            GomokuState after;
            try {
                // 计算AI落子前的步数
                int expStep2 = computeExpectedStep(now.board());
                // 执行AI落子
                after = gomokuService.place(roomId, mv.x(), mv.y(), mv.piece());
                // 创建AI落子后的游戏状态记录
                GameStateRecord rec2 = buildRecord(after, roomId, gameIdAtSchedule, expStep2 + 1);
                // 计算AI落子后的下一回合截止时间
                long nextDeadlineMs2 = after.over() ? 0L : System.currentTimeMillis() + turnSeconds * 1000L;
                // 原子性地保存AI落子后的状态到Redis
                gameStateRepository.updateAtomically(roomId, gameIdAtSchedule, expStep2, Board.WHITE, rec2, after.over() ? 0L : nextDeadlineMs2);
            } catch (Exception ignore2) { return; }
            // 广播AI落子后的游戏状态
            sendState(roomId, after);
        } catch (Exception ex) {
            // 如果AI落子过程中出现异常，发送错误消息给客户端
            sendError(roomId, ex.getMessage());
        } finally {
            // 无论成功失败，都要清理AI任务记录
            pendingAi.remove(roomId);
        }
    }

    /**
     * 统一广播最新局面 + 触发/停止回合计时
     * ----------------------------------------------------
     * 用途：
     *  - 落子、认输、重开等会改变棋盘状态的操作调用此方法；
     *  - 同时发送 STATE（增量棋盘状态）和 SNAPSHOT（房间全貌），前端可选择性使用。
     * 
     * 说明：
     *  - STATE 事件：包含当前盘状态 + 系列比分，用于增量更新棋盘；
     *  - SNAPSHOT 事件：包含房间全貌（座位、准备状态、phase、创建时间等），用于全量同步。
     */
    private void sendState(String roomId, GomokuState state) {
        // —— STATE 事件（增量更新棋盘）——
        SeriesView sv = gomokuService.getSeries(roomId);
        BroadcastEvent evt = new BroadcastEvent();
        evt.setRoomId(roomId);
        evt.setType("STATE");
        evt.setPayload(new StatePayload(state, sv));
        messaging.convertAndSend(topic(roomId), evt);

        // —— SNAPSHOT 事件（房间全貌，统一复用 broadcastSnapshot）——
        broadcastSnapshot(roomId);

        // —— 回合倒计时逻辑 ——
        coordinator.syncFromState(roomId, state);
    }

    private void sendError(String roomId, String msg) {
        BroadcastEvent err = new BroadcastEvent();
        err.setRoomId(roomId);
        err.setType("ERROR");
        err.setPayload(msg);
        messaging.convertAndSend(topic(roomId), err);
    }

    /**
     * 统一广播房间全貌快照（SNAPSHOT 事件）
     * ----------------------------------------------------
     * 用途：
     *  - 所有会改变房间“全貌”的操作（准备状态、房间状态、座位绑定等）统一调用此方法；
     *  - 前端收到 SNAPSHOT 后，用这份完整快照覆盖本地状态，确保所有客户端看到一致的房间全貌。
     * 
     * 说明：
     *  - 此方法会调用 gomokuService.snapshot(roomId) 从 Redis 聚合最新状态；
     *  - 不依赖内存 Room 对象，支持多节点部署。
     */
    private void broadcastSnapshot(String roomId) {
        GomokuSnapshot snap = gomokuService.snapshot(roomId);
        BroadcastEvent evt = new BroadcastEvent();
        evt.setRoomId(roomId);
        evt.setType("SNAPSHOT");
        evt.setPayload(snap);
        messaging.convertAndSend(topic(roomId), evt);
    }

    /**
     * 【已废弃】广播准备状态更新
     * 
     * @deprecated 请使用 broadcastSnapshot(roomId) 统一广播房间全貌。
     *             此方法保留仅为向后兼容，新代码不应调用。
     */
    @Deprecated
    private void sendReadyStatusUpdate(String roomId) {
        // 统一改为广播完整快照
        broadcastSnapshot(roomId);
    }

    /**
     * 【已废弃】广播房间状态更新
     * 
     * @deprecated 请使用 broadcastSnapshot(roomId) 统一广播房间全貌。
     *             此方法保留仅为向后兼容，新代码不应调用。
     */
    @Deprecated
    private void sendRoomStatusUpdate(String roomId) {
        // 统一改为广播完整快照
        broadcastSnapshot(roomId);
    }

    /** 拼接广播路径（示例：/topic/room.1234） */
    private String topic(String roomId) {
        return "/topic/room." + roomId;
    }




    /** WS推送的载荷：当前盘状态 + 多盘比分/局号视图 */
    class StatePayload {
        public GomokuState state;
        public SeriesView series;
        StatePayload(GomokuState s,
                     SeriesView v) {
            this.state = s; this.series = v;
        }
    }

    // ★ 首次坐下/绑定执子后，点对点推送 seatKey（用于刷新恢复）
    @Data
    static class SeatGranted {
        private final String seatKey;
        private final char   side;    // 'X' 或 'O'（你项目里 BLACK/WHITE 实际是字符）
    }

    // ★ when using sessionId as "user", add headers so convertAndSendToUser routes correctly
    // 发送到用户目的地时，默认按认证主体名路由，无需附加 headers
}
