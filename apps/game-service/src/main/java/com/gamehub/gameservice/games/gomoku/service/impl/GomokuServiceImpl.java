package com.gamehub.gameservice.games.gomoku.service.impl;

import com.gamehub.gameservice.application.user.UserDirectoryService;
import com.gamehub.gameservice.application.user.UserProfileView;
import com.gamehub.gameservice.games.gomoku.domain.ai.GomokuAI;
import com.gamehub.gameservice.games.gomoku.domain.dto.*;
import com.gamehub.gameservice.games.gomoku.domain.model.Game;
import com.gamehub.gameservice.games.gomoku.domain.model.Room;
import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.domain.enums.RoomPhase;
import com.gamehub.gameservice.games.gomoku.domain.model.*;
import com.gamehub.gameservice.games.gomoku.domain.repository.GameStateRepository;
import com.gamehub.gameservice.games.gomoku.domain.repository.RoomRepository;
import com.gamehub.gameservice.games.gomoku.domain.repository.TurnRepository;
import com.gamehub.gameservice.games.gomoku.domain.rule.GomokuJudge;
import com.gamehub.gameservice.games.gomoku.domain.rule.GomokuJudgeRenju;
import com.gamehub.gameservice.games.gomoku.domain.rule.Outcome;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import com.gamehub.gameservice.games.gomoku.application.TurnClockCoordinator;
import com.gamehub.gameservice.platform.ongoing.OngoingGameInfo;
import com.gamehub.gameservice.platform.ongoing.OngoingGameTracker;
import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.WebSocketSessionInfo;
import com.gamehub.gameservice.platform.ws.WebSocketDisconnectHelper;
import com.gamehub.gameservice.games.gomoku.domain.constants.GameMessages;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
@RequiredArgsConstructor
public class GomokuServiceImpl implements GomokuService {

    private static final Duration ROOM_TTL = Duration.ofHours(48);
    /** 座位锁 TTL：短时锁，防止并发抢座；释放/过期后可重试 */
    private static final Duration SEAT_LOCK_TTL = Duration.ofMinutes(2);
    /** 用户信息缓存 TTL（30分钟，平衡一致性与性能） */
    private static final Duration USER_PROFILE_CACHE_TTL = Duration.ofMinutes(30);


    // ====== 内存房间表 ======
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    // --- 强随机 & Base64URL（seatKey）
    private final SecureRandom seatKeyRnd = new SecureRandom();


    // ====== 新增：Redis 仓储 ======
    private final RoomRepository roomRepo;
    private final GameStateRepository gameRepo;
    private final TurnRepository turnRepo;
    private final UserDirectoryService userDirectoryService;
    private final SessionRegistry sessionRegistry;
    private final WebSocketDisconnectHelper disconnectHelper;
    private ObjectProvider<TurnClockCoordinator> coordinatorProvider;

    @Autowired
    public void setCoordinatorProvider(ObjectProvider<TurnClockCoordinator> coordinatorProvider) {
        this.coordinatorProvider = coordinatorProvider;
    }

    /** 每回合默认倒计时时长（秒） */
    @Value("${gomoku.turn.seconds:30}")
    private int turnSeconds;

    private final OngoingGameTracker ongoingGameTracker;


    /**
     * 创建新房间
     */
    @Override
    public String newRoom(Mode mode, Character aiPiece, Rule rule, String ownerUserId, String ownerName) {
        // 1) 基本参数与默认值
        Mode m  = (mode == null ? Mode.PVE : mode);
        Rule ru = (rule == null ? Rule.STANDARD : rule);
        char ai = (aiPiece == null ? Board.WHITE : aiPiece);

        // 2) 生成 roomId / gameId
        String roomId = UUID.randomUUID().toString();
        String gameId = UUID.randomUUID().toString();

        // 3) Redis：写入 RoomMeta
        RoomMeta meta = new RoomMeta();
        meta.setRoomId(roomId);
        meta.setGameId(gameId);
        meta.setMode(m.name());
        meta.setRule(ru.name());
        meta.setAiPiece(m == Mode.PVP ? null : String.valueOf(ai));
        meta.setCurrentIndex(1);
        meta.setBlackWins(0);
        meta.setWhiteWins(0);
        meta.setDraws(0);
        meta.setOwnerUserId(ownerUserId); // 保存房主用户ID
        meta.setOwnerName(ownerName != null && !ownerName.isBlank() ? ownerName : ownerUserId);
        long now = System.currentTimeMillis();
        meta.setCreatedAt(now); // 房间创建时间（统一在 Redis 中排序）
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        // 在线房间索引：用于大厅列表按创建时间倒序分页
        roomRepo.addRoomIndex(roomId, now, ROOM_TTL);

        // 3.1 记录用户正在进行中的房间，供前端“继续游戏”入口使用
        ongoingGameTracker.save(ownerUserId, OngoingGameInfo.gomoku(roomId));

        // 4) Redis：写入首盘 GameStateRecord（空盘，黑先）
        String emptyBoard = String.valueOf(Board.EMPTY).repeat(Board.SIZE * Board.SIZE);
        GameStateRecord rec = new GameStateRecord();
        rec.setRoomId(roomId);
        rec.setGameId(gameId);
        rec.setIndex(1);
        rec.setBoard(emptyBoard);
        rec.setCurrent(String.valueOf(Board.BLACK)); // 新盘默认黑先
        rec.setLastMove(null);
        rec.setWinner(null);
        rec.setOver(false);
        rec.setStep(0);
        gameRepo.save(roomId,gameId,rec, ROOM_TTL);

        // 5) Redis：初始化座位绑定，房主自动绑定黑方（先手）
        SeatsBinding seats = new SeatsBinding();
        seats.setSeatXSessionId(ownerUserId); // 房主默认绑定黑方（先手）
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);
        // TurnAnchor的创建交给TurnClockManager处理

        // 7) 缓存房主用户资料，供后续 snapshot 直接读取（避免 WS 场景调用 Feign）
        cacheUserProfile(roomId, ownerUserId);

        // 6) 内存快照：为现有控制器保留
        rooms.put(roomId, new Room(
                roomId, m, ru, (m == Mode.PVE ? ai : 0),
                new GomokuAI(3, ru == Rule.RENJU),gameId
        ));

        return roomId;
    }

    /**
     * 获取房间（内存优先，未命中则从 Redis 加载）
     */
    private Room room(String roomId) {
        // 1) 内存命中
        Room cached = rooms.get(roomId);
        if (cached != null) return cached;

        // 2) 取房间元信息（模式/规则/AI方/当前盘 gameId、比分、index）
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));

        // 2.1) 从 Redis series hash 读取最新比分（比分以 series hash 为准）
        SeriesView seriesView = roomRepo.getSeries(roomId);
        if (seriesView != null) {
            meta.setBlackWins(seriesView.getBlackWins());
            meta.setWhiteWins(seriesView.getWhiteWins());
            meta.setDraws(seriesView.getDraws());
            meta.setCurrentIndex(seriesView.getIndex());
        }

        // 3) 用已有转换器还原 Room（其中 series.current 已用 meta 构造）
        Room r = RoomMetaConverter.toRoom(meta);

        // 4) 回灌座位绑定（用于刷新重入）
        roomRepo.getSeats(roomId).ifPresent(seats -> {
            // seatBySession: Map<String,String> -> Map<String,Character>
            if (seats.getSeatBySession() != null && !seats.getSeatBySession().isEmpty()) {
                for (Map.Entry<String, String> e : seats.getSeatBySession().entrySet()) {
                    String sid = e.getKey();
                    String val = e.getValue();
                    if (sid == null || sid.isBlank() || val == null || val.isBlank()) continue;
                    char c = Character.toUpperCase(val.trim().charAt(0));
                    if (c == 'X' || c == 'O') {
                        r.getSeatBySession().put(sid, c);
                    }
                }
            }
            if (seats.getSeatXSessionId() != null) r.setSeatXSessionId(seats.getSeatXSessionId());
            if (seats.getSeatOSessionId() != null) r.setSeatOSessionId(seats.getSeatOSessionId());
        });

        // 5) 回灌当前盘棋局（以 Redis 为准；没有就空盘）
        final String gameId = meta.getGameId();
        GomokuState restored = gameRepo.get(roomId, gameId)
                .map(rec -> {
                    GomokuState s = new GomokuState();

                    // 5.1 棋盘字符串 -> 实盘
                    String boardStr = rec.getBoard();
                    if (boardStr != null && boardStr.length() == Board.SIZE * Board.SIZE) {
                        for (int x = 0; x < Board.SIZE; x++) {
                            for (int y = 0; y < Board.SIZE; y++) {
                                char p = boardStr.charAt(x * Board.SIZE + y);
                                if (p != Board.EMPTY) s.getBoard().place(x, y, p);
                            }
                        }
                    }

                    // 5.2 当前轮到方
                    if (rec.getCurrent() != null && !rec.getCurrent().isBlank()) {
                        s.setCurrent(Character.toUpperCase(rec.getCurrent().charAt(0)));
                    }

                    // 5.3 终局与胜者
                    String winner = rec.getWinner();
                    if(StringUtils.isNotBlank(winner)){
                        s.setOver(winner.charAt(0));
                    }
                    if (rec.getWinner() != null && !rec.getWinner().isBlank()) {
                        char w = Character.toUpperCase(rec.getWinner().trim().charAt(0));
                        if (w == 'X' || w == 'O') s.setWinner(w);
                        // "DRAW" 的场景保留 winner==null（你当前的状态模型即如此）
                    }

                    // 5.4 上一步坐标（存储格式 "x,y"）
                    if (rec.getLastMove() != null && !rec.getLastMove().isBlank()) {
                        String[] xy = rec.getLastMove().split(",");
                        if (xy.length == 2) {
                            try {
                                int lx = Integer.parseInt(xy[0].trim());
                                int ly = Integer.parseInt(xy[1].trim());
                                // 用棋盘上的真实子色，避免把 lastMove 的棋子色搞错
                                char lp = s.getBoard().get(lx, ly);
                                if (lp == Board.BLACK || lp == Board.WHITE) {
                                    s.setLastMove(new Move(lx, ly, lp));
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                    return s;
                })
                .orElseGet(GomokuState::new); // Redis 没有就空盘（黑先）

        // 5.5 灌回 series.current.state（不用 copyFrom，逐字段同步，避免你没有该方法）
        Game g = r.getSeries().getCurrent();
        g.setGameId(gameId); // 保证一致
        // 覆盖棋盘与元信息
        for (int x = 0; x < Board.SIZE; x++) {
            for (int y = 0; y < Board.SIZE; y++) {
                char p = restored.getBoard().get(x, y);
                if (p != Board.EMPTY) {
                    g.getState().getBoard().place(x, y, p);
                }
            }
        }
        if(restored.getWinner()!=null){
            g.getState().setOver(restored.getWinner());
        }
        g.getState().setCurrent(restored.getCurrent());
        g.getState().setLastMove(restored.getLastMove());

        // 6)（可选）TurnAnchor 仅供计时展示，交由 TurnClockManager 使用时再读，不强灌入 Room

        // 7) 放入缓存返回
        rooms.put(roomId, r);
        return r;
    }

    /**
     * 落子
     */
    @Override
    public GomokuState place(String roomId, int x, int y, char piece) {
        Room r = room(roomId);
        
        // 检查房间状态：WAITING状态下不允许落子
        RoomPhase phase = getRoomPhase(roomId);
        if (phase == RoomPhase.WAITING) {
            throw new IllegalStateException(GameMessages.GAME_NOT_STARTED);
        }
        
        GomokuState s = r.getSeries().getCurrent().getState();  // 必须落到"当前盘"

        //已结束局直接返回
        if (s.over()) return s;
        //回合锁：必须是当前执子
        if (s.current() != piece) {
            throw new IllegalStateException(GameMessages.formatNotYourTurn(String.valueOf(s.current())));
        }
        //合法性：坐标合法 & 未被占用
        if (!GomokuJudge.isLegal(s.board(), x, y)) {
            throw new IllegalArgumentException(GameMessages.ILLEGAL_MOVE);
        }
        // 已移除：边界限制已移除，允许在 0-14 的所有交叉点落子
        // 15x15 棋盘有 15 个交叉点（0-14），都可以落子
        // 黑方禁手（仅在 RENJU 模式判断）
        if (r.getRule() == Rule.RENJU && piece == Board.BLACK
                && GomokuJudgeRenju.isForbiddenMove(s.board(), x, y)) {
            throw new IllegalArgumentException(GameMessages.FORBIDDEN_MOVE_DETAIL);
        }

        // 玩家下子
        s.apply(new Move(x, y, piece));

        // 胜负判定
        Outcome oc = GomokuJudge.outcomeAfterMove(s.board(), x, y, piece);
        //胜负判定
        if (oc != Outcome.ONGOING) {
            if (oc == Outcome.BLACK_WIN) {
                s.setOver(Board.BLACK); r.getSeries().incBlackWins();
            } else if (oc == Outcome.WHITE_WIN) {
                s.setOver(Board.WHITE); r.getSeries().incWhiteWins();
            } else {
                s.setOver(Board.EMPTY); r.getSeries().incDraws();
            }
            // 1) 统计：X/O 胜 或 和棋(null)
            roomRepo.incrSeriesOnFinish(roomId, s.getWinner());
            // 2) 清理回合计时锚点（第5步配套）
            turnRepo.delete(roomId);
            // 3) 游戏结束：重置准备状态，房间状态回到 WAITING（下一局重新准备）
            resetAllReady(roomId);
            setRoomPhase(roomId, RoomPhase.WAITING);
            return s;
        }
        // 切换回合
        s.setCurrent(piece == Board.BLACK ? Board.WHITE : Board.BLACK);

        // TurnAnchor的创建和管理交给TurnClockManager处理
        // 这里不再手动创建TurnAnchor，让TurnClockManager在restart时处理

        return s;
    }

    /**
     * AI 建议落子位置
     */
    @Override
    public Move suggest(String roomId, char side) {
        Room r = room(roomId);
        return r.getAi().bestMove(r.getSeries().getCurrent().getState().board(), side); // 建议同样遵循禁手与威胁优先
    }

    /**
     * 重开当前盘（重置棋盘，保留比分）
     */
    @Override
    public GomokuState restart(String roomId) {
        Room r = room(roomId);
        String gameId = UUID.randomUUID().toString();
        
        // 更新内存中的Room
        rooms.put(r.getId(), new Room(r.getId(),r.getMode(), r.getRule(), r.getAiPiece(),
                new GomokuAI(3, r.getRule() == Rule.RENJU),gameId));
        
        // 更新Redis中的RoomMeta.gameId
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        meta.setGameId(gameId);
        meta.setCurrentIndex(1); // 重开时重置为第1盘
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        
        // 在Redis中创建新的GameStateRecord（空盘）
        String emptyBoard = String.valueOf(Board.EMPTY).repeat(Board.SIZE * Board.SIZE);
        GameStateRecord rec = new GameStateRecord();
        rec.setRoomId(roomId);
        rec.setGameId(gameId);
        rec.setIndex(1);
        rec.setBoard(emptyBoard);
        rec.setCurrent(String.valueOf(Board.BLACK)); // 新盘默认黑先
        rec.setLastMove(null);
        rec.setWinner(null);
        rec.setOver(false);
        rec.setStep(0);
        gameRepo.save(roomId, gameId, rec, ROOM_TTL);
        
        // TurnAnchor的创建交给TurnClockManager处理
        
        return rooms.get(r.getId()).getSeries().getCurrent().getState();
    }

    /**
     * 获取当前盘状态
     */
    @Override
    public GomokuState getState(String roomId) {
        // 若 GomokuState 可变，推荐返回副本；否则可直接返回
        return getRoomOrThrow(roomId).getSeries().getCurrent().getState();
    }


    /**
     * 落子并自动触发 AI 回手（PVE 模式）
     */
    @Override
    public GomokuState placeAndAutoIfNeeded(String roomId, int x, int y, char piece) {
        Room r = getRoomOrThrow(roomId);

        // 3.1 玩家先走这一步
        GomokuState afterPlayer = place(roomId, x, y, piece);

        // 3.2 若是 PVE 且玩家这步不是 AI 的棋色，则让 AI 紧接着走一步
        if (r.getMode() == Mode.PVE && piece != r.getAiPiece()) {
            // 用你现有的 suggest 统一拿推荐点
            Move aiMove = suggest(roomId, r.getAiPiece());
            // 保护：建议可能返回 null（极端终局），需判空
            if (aiMove != null) {
                return place(roomId, aiMove.x(), aiMove.y(), aiMove.piece());
            }
        }
        return afterPlayer;
    }

    /**
     * 获取房间模式（PVE/PVP）
     */
    @Override
    public Mode getMode(String roomId) {
        Room r = room(roomId);
        if (r == null) throw new IllegalArgumentException("ROOM_NOT_FOUND");
        return r.getMode();
    }

    /**
     * 获取 AI 棋子颜色
     */
    @Override
    public char getAiPiece(String roomId) {
        Room r = room(roomId);
        if (r == null) throw new IllegalArgumentException("ROOM_NOT_FOUND");
        return r.getAiPiece();
    }

    /**
     * 分配或恢复座位（X/O）并绑定用户
     */
    @Override
    public char resolveAndBindSide(String roomId, String userId, Character wantSide) {
        Room r = room(roomId); // 仍复用内存 Room（含 rule/mode/ai 等）
        Mode m = r.getMode();

        // 1) 读取 Redis 座位绑定
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        //黑用户
        String curX = seats.getSeatXSessionId();
        //白用户
        String curO = seats.getSeatOSessionId();

        // 若该 session 已经占座，直接返回原座位（避免“房间已满”误判）
        if (userId != null && !userId.isBlank()) {
            if (curX != null && curX.equals(userId)) return Board.BLACK;
            if (curO != null && curO.equals(userId)) return Board.WHITE;
        }

        // 2) 计算要绑定的座位
        char side;
        if (m == Mode.PVE) {
            // PVE：玩家拿与 AI 相反的一方
            char ai = r.getAiPiece();
            char playerSide = (ai == Board.BLACK ? Board.WHITE : Board.BLACK);
            
            // 检查玩家座位是否已被占用
            String occupiedUserId = (playerSide == Board.BLACK) ? curX : curO;
            if (occupiedUserId != null && !occupiedUserId.isBlank()) {
                // 座位已被占用
                if (!occupiedUserId.equals(userId)) {
                    // 被其他人占用，拒绝绑定
                    throw new IllegalStateException("PVE模式下，玩家座位已被占用，无法绑定");
                }
                // 是自己占用的，直接返回
                side = playerSide;
            } else {
                // 座位空闲，可以绑定
                side = playerSide;
            }
        } else {
            // PVP：不再支持意向座位，统一按顺序自动分配（先黑后白），已被占用则抛错
            if (curX == null || curX.isBlank()) {
                side = Board.BLACK;
            } else if (curO == null || curO.isBlank()) {
                side = Board.WHITE;
            } else {
                throw new IllegalStateException("房间已满");
            }
        }

        // 3) 并发占座保护：先占用座位锁，确保同一时刻只能有一人写入
        boolean locked = roomRepo.tryLockSeat(roomId, side, userId, SEAT_LOCK_TTL);
        if (!locked) {
            throw new IllegalStateException("座位已被占用");
        }
        try {
            // 回写 Redis 绑定（同 sessionId 覆盖）
            if (side == Board.BLACK) {
                seats.setSeatXSessionId(userId);
            } else {
                seats.setSeatOSessionId(userId);
            }
            seats.getSeatBySession().put(userId, String.valueOf(side));
            roomRepo.saveSeats(roomId, seats, ROOM_TTL);
        } finally {
            // 占座完成后即释放锁，避免短期重复进入被误判
            roomRepo.releaseSeatLock(roomId, side, userId);
        }

        // 4) 同步到内存快照（兼容现有逻辑）
        r.getSeatBySession().put(userId, side);
        if (side == Board.BLACK) r.setSeatXSessionId(userId);
        else r.setSeatOSessionId(userId);

        return side;
    }

    /**
     * 获取当前盘 gameId
     */
    @Override
    public String getGameId(String roomId) {
        return room(roomId).getSeries().getCurrent().getGameId();
    }

    /**
     * 获取系列赛信息（当前盘号、比分等）
     */
    @Override
    public SeriesView getSeries(String roomId) {
        roomRepo.getSeries(roomId);
        Room r = room(roomId);
        Game g = r.getSeries().getCurrent();
        GomokuState s = g.getState();
        Character win = s.over() ? s.winner() : null;
        return new SeriesView(
                g.getIndex(), g.getGameId(),
                r.getSeries().getBlackWins(), r.getSeries().getWhiteWins(), r.getSeries().getDraws(),
                s.over(), win
        );
    }

    /**
     * 开启新盘（系列赛中）
     */
    @Override
    public GomokuState newGame(String roomId) {
        Room r = room(roomId);
        // 1) 取消旧盘 AI 任务
        var old = r.getSeries().getCurrent()!= null ? r.getSeries().getCurrent().getPendingAi() : null;
        if (old != null) old.cancel(false);

        // 2) 创建新盘，并推进局号（nextIndex 自增）
        int index = r.getSeries().getNextIndex();
        r.getSeries().setNextIndex(index + 1);
        String gameId = UUID.randomUUID().toString();
        Game g = new Game(index, gameId);
        r.getSeries().setCurrent(g);
        
        // 3) 更新Redis中的RoomMeta.gameId和currentIndex
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        meta.setGameId(gameId);
        meta.setCurrentIndex(index);
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        
        // 4) 在Redis中创建新的GameStateRecord（空盘）
        String emptyBoard = String.valueOf(Board.EMPTY).repeat(Board.SIZE * Board.SIZE);
        GameStateRecord rec = new GameStateRecord();
        rec.setRoomId(roomId);
        rec.setGameId(gameId);
        rec.setIndex(index);
        rec.setBoard(emptyBoard);
        rec.setCurrent(String.valueOf(Board.BLACK)); // 新盘默认黑先
        rec.setLastMove(null);
        rec.setWinner(null);
        rec.setOver(false);
        rec.setStep(0);
        gameRepo.save(roomId, gameId, rec, ROOM_TTL);
        
        return g.getState();
    }

    /**
     * 认输
     */
    @Override
    public GomokuState resign(String roomId, char side) {
        Room r = room(roomId);
        GomokuState s = r.getSeries().getCurrent().getState();
        if (s.over()) return s;
        if (side == Board.BLACK) { s.setOver(Board.WHITE); r.getSeries().incWhiteWins(); }
        else { s.setOver(Board.BLACK); r.getSeries().incBlackWins(); }
        //终局清理
        roomRepo.incrSeriesOnFinish(roomId, s.getWinner()); // X 或 O
        turnRepo.delete(roomId);
        // 游戏结束：重置准备状态，房间状态回到 WAITING（下一局重新准备）
        resetAllReady(roomId);
        setRoomPhase(roomId, RoomPhase.WAITING);
        return s;
    }

    /**
     * 为座位签发 seatKey（无账号阶段的最小鉴权）
     * @param roomId    房间ID
     * @param seat      'X' 或 'O'
     * @param userId   用户ID
     * @param seatKey
     * @return
     */
    @Override
    public String issueSeatKey(String roomId, char seat, String userId,String seatKey) {
        char s = normSeat(seat); // 'X' 或 'O'
        //获取seatkey绑定坐席,该坐席已有seatkey,不再生成
        Character sideStr = roomRepo.getSeatKey(roomId, seatKey);
        if(sideStr != null) return null;

        Room r =  room(roomId);
        // 生成 144bit 随机串，Base64URL 无填充
        byte[] buf = new byte[18];
        seatKeyRnd.nextBytes(buf);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        // 记录映射；同座位可签发多把钥匙（看你策略），通常只保留最新一把：
        // 可选：吊销旧钥匙（如果你要“后签发覆盖前签发”）
        // r.seatKeyToSeat.entrySet().removeIf(e -> e.getValue() == s);

        r.getSeatKeyToSeat().put(key, s);
        if (userId != null && !userId.isBlank()) {
            r.getSeatToSessionId().put(s, userId);
        }
        // === 写入 Redis，用于刷新重入 ===
        roomRepo.setSeatKey(roomId, key, String.valueOf(s), ROOM_TTL);
        return key;
    }

    /**
     * 刷新/重连：用 seatKey 绑定当前会话为该座位
     * @param roomId       房间ID
     * @param seatKey      之前签发给该座位的 seatKey（浏览器在刷新后携带）
     * @param userId       用户ID
     * @return
     */
    @Override
    public Character bindBySeatKey(String roomId, String seatKey, String userId) {
        if (seatKey == null || seatKey.isBlank()) return null;

        // 1) 根据 seatKey 解析座位（'X' 或 'O'）
        Character seat = roomRepo.getSeatKey(roomId, seatKey);
        if (seat == null) return null;
        // userId 为空时，只做 seat 解析返回，不进行绑定写操作
        if (userId == null || userId.isBlank()) return seat;


        // 2) 读取 SeatsBinding，判断是否需要重绑
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        String oldSessionId = (seat == Board.BLACK) ? seats.getSeatXSessionId() : seats.getSeatOSessionId();

        // 2.1 反向索引幂等补齐（成本极低，重复 put 不会改变语义）
        seats.getSeatBySession().put(userId, String.valueOf(seat));

        // 2.2 相同 session → 短路返回：无需写 Redis（避免无意义 IO）
        if (java.util.Objects.equals(oldSessionId, userId)) {
            // —— 可选：如果你想做“滑动过期”，这里可以调用一个 touchSeats(roomId, ttl) 续期；
            // roomRepo.touchSeats(roomId, ROOM_TTL);
            // 内存快照幂等同步
            Room rSame = room(roomId);

            if (rSame != null) {
                rSame.getSeatBySession().put(userId, seat);
                if (seat == Board.BLACK) rSame.setSeatXSessionId(userId);
                else                     rSame.setSeatOSessionId(userId);
            }
            return seat;
        }
        // 2.3 session 发生变化 / 首次绑定该座位 → 更新并落库
        if (seat == Board.BLACK) {
            seats.setSeatXSessionId(userId);
        } else {
            seats.setSeatOSessionId(userId);
        }
        // TTL 视你的策略，目前与你现有实现一致：48 小时
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);

        // 3) 同步到内存快照（幂等）
        Room r = room(roomId);
        if (r != null) {
            r.getSeatBySession().put(userId, seat);
            if (seat == Board.BLACK) r.setSeatXSessionId(userId);
            else                     r.setSeatOSessionId(userId);
        }
        return seat;
    }

    /**
     * 生成只读快照（FullSync 数据源）
     * @param roomId
     * @return
     */
    @Override
    public GomokuSnapshot snapshot(String roomId) {
        RoomView view = assembleRoomView(roomId);
        RoomMeta meta = view.getMeta();
        SeatsBinding seats = view.getSeats();
        GameStateRecord rec = view.getGame();
        com.gamehub.gameservice.games.gomoku.domain.dto.TurnAnchor anchor = view.getTurnAnchor();

        boolean seatXOccupied = view.isSeatXOccupied();
        boolean seatOOccupied = view.isSeatOOccupied();
        String seatXUserId = seats.getSeatXSessionId();
        String seatOUserId = seats.getSeatOSessionId();

        // 查询两侧玩家的详细信息（优先读缓存，避免 WS 场景调用 Feign）
        UserProfileView seatXUserInfo = roomRepo.getUserProfile(roomId, seatXUserId).orElse(null);
        UserProfileView seatOUserInfo = roomRepo.getUserProfile(roomId, seatOUserId).orElse(null);

        // 查询两侧玩家的WebSocket连接状态
        boolean seatXConnected = seatXUserId != null && !seatXUserId.isBlank() 
                && !sessionRegistry.getWebSocketSessions(seatXUserId).isEmpty();
        boolean seatOConnected = seatOUserId != null && !seatOUserId.isBlank() 
                && !sessionRegistry.getWebSocketSessions(seatOUserId).isEmpty();

        Character sideToMove = view.getSideToMove();
        Long turnSeq = anchor != null ? anchor.getTurnSeq() : 0L;
        Long deadline = view.getDeadlineEpochMs();

        final int n = Board.SIZE;
        char[][] cells = new char[n][n];
        String s = rec.getBoard();
        if (s != null && s.length() == n * n) {
            for (int x = 0; x < n; x++) {
                for (int y = 0; y < n; y++) {
                    cells[x][y] = s.charAt(x * n + y);
                }
            }
        } else {
            for (int x = 0; x < n; x++) {
                java.util.Arrays.fill(cells[x], Board.EMPTY);
            }
        }

        String outcome = null;
        if (rec.isOver()) {
            if ("DRAW".equals(rec.getWinner())) {
                outcome = "DRAW";
            } else if ("X".equals(rec.getWinner())) {
                outcome = "X_WIN";
            } else if ("O".equals(rec.getWinner())) {
                outcome = "O_WIN";
            }
        }

        String modeStr = meta.getMode();
        String ruleStr = meta.getRule();
        Character aiSide = null;
        if ("PVE".equalsIgnoreCase(modeStr) && meta.getAiPiece() != null && !meta.getAiPiece().isBlank()) {
            aiSide = meta.getAiPiece().charAt(0);
        }

        SeriesView sv = getSeries(roomId);
        int round = sv.getIndex();
        int scoreX = sv.getBlackWins();
        int scoreO = sv.getWhiteWins();

        String phase = meta.getPhase();
        if (phase == null || phase.isBlank()) {
            phase = RoomPhase.WAITING.name();
        }
        java.util.Map<String, Boolean> readyStatus =
                seats.getReadyByUserId() == null ? java.util.Collections.emptyMap() : seats.getReadyByUserId();

        return new GomokuSnapshot(
                roomId,
                seatXOccupied,
                seatOOccupied,
                seatXUserId,
                seatOUserId,
                seatXUserInfo,
                seatOUserInfo,
                meta.getCreatedAt(),
                modeStr,
                aiSide,
                ruleStr,
                phase,
                n,
                cells,
                sideToMove,
                turnSeq == null ? 0L : turnSeq,
                deadline,
                round,
                scoreX,
                scoreO,
                outcome,
                readyStatus,
                seatXConnected,
                seatOConnected,
                meta.getOwnerUserId()
        );
    }

    /**
     * 缓存用户信息到 Redis
     */
    @Override
    public void cacheUserProfile(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            UserProfileView profile = userDirectoryService.getUserInfo(userId);
            if (profile != null) {
                roomRepo.saveUserProfile(roomId, userId, profile, USER_PROFILE_CACHE_TTL);
            } else {
                log.warn("获取用户信息为空: roomId={}, userId={}", roomId, userId);
            }
        } catch (Exception e) {
            log.warn("缓存用户信息失败: roomId={}, userId={}", roomId, userId, e);
            // 保底：出现异常时忽略，不影响主流程
        }
    }

    /**
     * 组装房间视图（用于 snapshot）
     */
    private RoomView assembleRoomView(String roomId) {
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));

        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        boolean seatXOccupied = seats.getSeatXSessionId() != null && !seats.getSeatXSessionId().isBlank();
        boolean seatOOccupied = seats.getSeatOSessionId() != null && !seats.getSeatOSessionId().isBlank();

        String gameId = meta.getGameId();
        GameStateRecord rec = gameRepo.get(roomId, gameId)
                .orElseGet(() -> {
                    GameStateRecord r0 = new GameStateRecord();
                    r0.setRoomId(roomId);
                    r0.setGameId(gameId);
                    r0.setIndex(Math.max(meta.getCurrentIndex(), 1));
                    r0.setBoard(String.valueOf(Board.EMPTY).repeat(Board.SIZE * Board.SIZE));
                    r0.setCurrent(String.valueOf(Board.BLACK));
                    r0.setLastMove(null);
                    r0.setWinner(null);
                    r0.setOver(false);
                    return r0;
                });

        TurnAnchor anchor = turnRepo.get(roomId).orElse(null);
        Long deadline = null;
        Character sideToMove = null;
        if (anchor != null) {
            deadline = anchor.getDeadlineEpochMs();
            sideToMove = (anchor.getSide() == null || anchor.getSide().isBlank()) ? null : anchor.getSide().charAt(0);
        } else if (!rec.isOver() && rec.getCurrent() != null && !rec.getCurrent().isBlank()) {
            sideToMove = rec.getCurrent().charAt(0);
        }

        return new RoomView(
                meta,
                seats,
                rec,
                anchor,
                seatXOccupied,
                seatOOccupied,
                sideToMove,
                deadline
        );
    }
    /**
     * 离开房间（PVE 模式会销毁房间，PVP 模式释放座位）
     */
    @Override
    public LeaveResult leaveRoom(String roomId, String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));

        // PVE 模式：直接销毁房间
        Mode mode = Mode.valueOf(meta.getMode());
        if (mode == Mode.PVE) {
            destroyRoom(roomId);
            ongoingGameTracker.clear(userId);
            return new LeaveResult(true, null, null);
        }

        // 判断用户是否在房间内
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        boolean isX = userId.equals(seats.getSeatXSessionId());
        boolean isO = userId.equals(seats.getSeatOSessionId());

        if (!isX && !isO) {
            ongoingGameTracker.clear(userId);
            return new LeaveResult(false, meta.getOwnerUserId(), null);
        }

        // 检查对手是否存在
        String opponentUserId = isX ? seats.getSeatOSessionId() : seats.getSeatXSessionId();
        boolean opponentPresent = opponentUserId != null && !opponentUserId.isBlank();
        char freedSeat = isX ? Board.BLACK : Board.WHITE;

        // 对手不存在：销毁房间
        if (!opponentPresent) {
            destroyRoom(roomId);
            ongoingGameTracker.clear(userId);
            return new LeaveResult(true, null, freedSeat);
        }

        // 释放座位（Redis）
        if (isX) {
            seats.setSeatXSessionId(null);
        } else {
            seats.setSeatOSessionId(null);
        }
        if (seats.getSeatBySession() != null) {
            seats.getSeatBySession().remove(userId);
        }
        // 回写座位绑定并释放座位锁
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);
        roomRepo.releaseSeatLock(roomId, freedSeat, userId);

        // 释放座位（内存）
        Room local = rooms.get(roomId);
        if (local != null) {
            local.getSeatBySession().remove(userId);
            if (isX) {
                local.setSeatXSessionId(null);
            } else {
                local.setSeatOSessionId(null);
            }
        }

        // 房主转移
        String newOwner = meta.getOwnerUserId();
        if (userId.equals(meta.getOwnerUserId())) {
            newOwner = opponentUserId;
            meta.setOwnerUserId(newOwner);
            // 获取新房主的用户信息并更新 ownerName
            try {
                UserProfileView newOwnerProfile = userDirectoryService.getUserInfo(newOwner);
                if (newOwnerProfile != null) {
                    String newOwnerName = newOwnerProfile.getDisplayName();
                    meta.setOwnerName(newOwnerName != null && !newOwnerName.isBlank() ? newOwnerName : newOwner);
                } else {
                    meta.setOwnerName(newOwner);
                }
            } catch (Exception e) {
                log.warn("获取新房主信息失败，使用 userId 作为 ownerName: {}", newOwner, e);
                meta.setOwnerName(newOwner);
            }
            roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        }

        // 清空对局状态（比分、轮数、当前局棋盘）
        // 因为玩家组合变了，之前的比分不再有效
        roomRepo.deleteSeries(roomId);
        meta.setBlackWins(0);
        meta.setWhiteWins(0);
        meta.setDraws(0);
        meta.setCurrentIndex(1);
        
        // 创建新的空棋盘
        String newGameId = UUID.randomUUID().toString();
        meta.setGameId(newGameId);
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        
        // 清空当前局的棋盘状态（创建新的空棋盘记录）
        String emptyBoard = String.valueOf(Board.EMPTY).repeat(Board.SIZE * Board.SIZE);
        GameStateRecord emptyRec = new GameStateRecord();
        emptyRec.setRoomId(roomId);
        emptyRec.setGameId(newGameId);
        emptyRec.setIndex(1);
        emptyRec.setBoard(emptyBoard);
        emptyRec.setCurrent(String.valueOf(Board.BLACK));
        emptyRec.setLastMove(null);
        emptyRec.setWinner(null);
        emptyRec.setOver(false);
        emptyRec.setStep(0);
        gameRepo.save(roomId, newGameId, emptyRec, ROOM_TTL);
        
        // 更新内存中的 Room 对象（如果存在）
        if (local != null) {
            local.getSeries().setBlackWins(0);
            local.getSeries().setWhiteWins(0);
            local.getSeries().setDraws(0);
            local.getSeries().setNextIndex(1);
            // 重新创建空棋盘（通过创建新的 Game 对象）
            Game newGame = new Game(1, newGameId);
            local.getSeries().setCurrent(newGame);
        }
        
        // 重置房间状态
        RoomPhase currentPhase = getRoomPhase(roomId);
        if (currentPhase == RoomPhase.PLAYING) {
            setRoomPhase(roomId, RoomPhase.WAITING);
        }

        // 重置准备状态
        resetAllReady(roomId);

        ongoingGameTracker.clear(userId);
        return new LeaveResult(false, newOwner, freedSeat);
    }




    // 放在类里其他方法附近即可 —— 私有工具：取房间或抛错
    private Room getRoomOrThrow(String roomId) {
        Room r = room(roomId); // ← 你已有的 Map<String, Room> rooms
        if (r == null) throw new IllegalArgumentException("Room not found: " + roomId);
        return r;
    }

    /**
     * 销毁房间（清理所有相关数据）
     */
    private void destroyRoom(String roomId) {
        rooms.remove(roomId);
        roomRepo.deleteRoom(roomId);
        roomRepo.deleteSeats(roomId);
        roomRepo.deleteSeatKeys(roomId);
        roomRepo.deleteSeatLocks(roomId);
        roomRepo.deleteSeries(roomId);
        roomRepo.deleteAllUserProfiles(roomId);
        gameRepo.deleteAll(roomId);
        turnRepo.delete(roomId);
        stopClock(roomId);
    }

    /**
     * 停止回合计时器
     */
    private void stopClock(String roomId) {
        TurnClockCoordinator coordinator = coordinatorProvider.getIfAvailable();
        if (coordinator != null) {
            coordinator.stop(roomId);
        }
    }

    /**
     * 判断指定房间是否是人机对战（PVE）模式。
     * - 用于 WebSocket 控制器判断：是否需要在玩家落子后触发 AI 回手。
     * - 若返回 false，则是玩家对战（PVP），不会自动调用 AI。
     *
     * @param roomId 房间ID
     * @return true 表示该房间是人机对战模式（PVE），false 表示玩家对战（PVP）
     */
    public boolean isPve(String roomId) { return room(roomId).getMode() == Mode.PVE; }

    /**
     * 获取房间的房主用户ID
     * @param roomId 房间ID
     * @return 房主用户ID
     */
    @Override
    public String getOwnerUserId(String roomId) {
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        return meta.getOwnerUserId();
    }

    /**
     * 房主踢出玩家
     * @param roomId 房间ID
     * @param ownerUserId 房主用户ID（调用者）
     * @param targetUserId 被踢玩家用户ID
     * @return 踢人结果
     * @throws IllegalStateException 如果不是房主、房间状态不是WAITING、目标不在房间、或目标是自己
     */
    @Override
    public KickResult kickPlayer(String roomId, String ownerUserId, String targetUserId) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");

        // 1. 权限检查：必须是房主
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException(GameMessages.ROOM_NOT_FOUND + ": " + roomId));
        String actualOwner = meta.getOwnerUserId();
        if (!ownerUserId.equals(actualOwner)) {
            throw new IllegalStateException(GameMessages.ONLY_OWNER_CAN_KICK);
        }

        // 2. 不能踢自己
        if (targetUserId.equals(ownerUserId)) {
            throw new IllegalStateException(GameMessages.CANNOT_KICK_SELF);
        }

        // 3. 房间状态检查：必须是WAITING状态
        RoomPhase phase = getRoomPhase(roomId);
        if (phase != RoomPhase.WAITING) {
            throw new IllegalStateException(GameMessages.CANNOT_KICK_IN_GAME);
        }

        // 4. 模式检查：必须是PVP模式
        Mode mode = Mode.valueOf(meta.getMode());
        if (mode == Mode.PVE) {
            throw new IllegalStateException(GameMessages.PVE_MODE_NO_KICK);
        }

        // 5. 目标玩家检查：必须在房间内
        if (!isUserInRoom(roomId, targetUserId)) {
            throw new IllegalStateException(GameMessages.TARGET_NOT_IN_ROOM);
        }

        // 6. 确定目标座位
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        boolean isX = targetUserId.equals(seats.getSeatXSessionId());
        boolean isO = targetUserId.equals(seats.getSeatOSessionId());
        if (!isX && !isO) {
            throw new IllegalStateException(GameMessages.TARGET_NOT_IN_ROOM);
        }
        char freedSeat = isX ? Board.BLACK : Board.WHITE;

        // 7. 检查对手是否存在
        String opponentUserId = isX ? seats.getSeatOSessionId() : seats.getSeatXSessionId();
        boolean opponentPresent = opponentUserId != null && !opponentUserId.isBlank();
        
        // 8. 如果对手不存在，销毁房间
        if (!opponentPresent) {
            destroyRoom(roomId);
            ongoingGameTracker.clear(targetUserId);
            return new KickResult(true, null, freedSeat, null);
        }

        // 9. 释放座位（Redis）
        if (isX) {
            seats.setSeatXSessionId(null);
        } else {
            seats.setSeatOSessionId(null);
        }
        if (seats.getSeatBySession() != null) {
            seats.getSeatBySession().remove(targetUserId);
        }
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);

        // 10. 释放座位（内存）
        Room local = rooms.get(roomId);
        if (local != null) {
            local.getSeatBySession().remove(targetUserId);
            if (isX) {
                local.setSeatXSessionId(null);
            } else {
                local.setSeatOSessionId(null);
            }
        }

        // 11. 房主保持不变（被踢的永远不可能是房主，因为房主不能踢自己）
        String newOwner = meta.getOwnerUserId();

        // 12. 清空对局状态（比分、轮数、当前局棋盘）
        roomRepo.deleteSeries(roomId);
        meta.setBlackWins(0);
        meta.setWhiteWins(0);
        meta.setDraws(0);
        meta.setCurrentIndex(1);
        
        // 13. 创建新的空棋盘
        String newGameId = UUID.randomUUID().toString();
        meta.setGameId(newGameId);
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        
        // 14. 清空当前局的棋盘状态
        String emptyBoard = String.valueOf(Board.EMPTY).repeat(Board.SIZE * Board.SIZE);
        GameStateRecord emptyRec = new GameStateRecord();
        emptyRec.setRoomId(roomId);
        emptyRec.setGameId(newGameId);
        emptyRec.setIndex(1);
        emptyRec.setBoard(emptyBoard);
        emptyRec.setCurrent(String.valueOf(Board.BLACK));
        emptyRec.setLastMove(null);
        emptyRec.setWinner(null);
        emptyRec.setOver(false);
        emptyRec.setStep(0);
        gameRepo.save(roomId, newGameId, emptyRec, ROOM_TTL);
        
        // 15. 更新内存中的 Room 对象
        if (local != null) {
            local.getSeries().setBlackWins(0);
            local.getSeries().setWhiteWins(0);
            local.getSeries().setDraws(0);
            local.getSeries().setNextIndex(1);
            Game newGame = new Game(1, newGameId);
            local.getSeries().setCurrent(newGame);
        }
        
        // 16. 重置房间状态为WAITING（确保状态正确）
        setRoomPhase(roomId, RoomPhase.WAITING);

        // 17. 重置准备状态
        resetAllReady(roomId);

        // 18. 强制断开被踢玩家的WebSocket连接
        try {
            java.util.List<WebSocketSessionInfo> sessions = sessionRegistry.getWebSocketSessions(targetUserId);
            if (sessions != null && !sessions.isEmpty()) {
                for (WebSocketSessionInfo session : sessions) {
                    // 发送踢人通知
                    disconnectHelper.sendKickMessage(targetUserId, session.getSessionId(), GameMessages.KICKED_OUT_REASON);
                    // 强制断开连接
                    disconnectHelper.forceDisconnect(session.getSessionId());
                }
                log.info("已断开被踢玩家的WebSocket连接: targetUserId={}, sessionCount={}", targetUserId, sessions.size());
            }
        } catch (Exception e) {
            log.warn("断开被踢玩家WebSocket连接失败: targetUserId={}", targetUserId, e);
            // 不影响踢人流程，继续执行
        }

        // 19. 清理被踢玩家的ongoing-game
        ongoingGameTracker.clear(targetUserId);

        return new KickResult(true, null, freedSeat, newOwner);
    }

    /**
     * 获取指定房间中 AI 所使用的棋子颜色。
     * - 在人机模式下，每个房间都会记录 AI 是使用 'X'（黑棋）还是 'O'（白棋）。
     * - 控制器在判断“轮到 AI 走”时会用到此值。
     * - 在 PVP 模式下也可安全调用，但意义不大。
     *
     * @param roomId 房间ID
     * @return AI 使用的棋子字符：'X' 或 'O'
     */
    public char aiPieceOf(String roomId) { return room(roomId).getAiPiece(); }

    /**
     * 工具方法
     * @param c
     * @return
     */
    private static char normSeat(char c) {
        char s = Character.toUpperCase(c);
        if (s != 'X' && s != 'O') throw new IllegalArgumentException("seat must be X or O");
        return s;
    }

    /**
     * 工具方法
     * @param r
     * @param seat
     * @return
     */
    private static boolean containsSeat(Room r, char seat) {
        char s = Character.toUpperCase(seat);
        for (Map.Entry<String, Character> e : r.getSeatKeyToSeat().entrySet()) {
            if (Objects.equals(e.getValue(), s)) return true;
        }
        return false;
    }

    // ========== 准备状态管理 ==========

    /**
     * 切换准备状态
     */
    @Override
    public boolean toggleReady(String roomId, String userId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() == null) {
            seats.setReadyByUserId(new HashMap<>());
        }
        boolean currentReady = seats.getReadyByUserId().getOrDefault(userId, false);
        boolean newReady = !currentReady;
        seats.getReadyByUserId().put(userId, newReady);
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);
        return newReady;
    }

    /**
     * 获取指定玩家的准备状态
     */
    @Override
    public boolean getReadyStatus(String roomId, String userId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() == null) {
            return false;
        }
        return seats.getReadyByUserId().getOrDefault(userId, false);
    }

    /**
     * 获取所有玩家的准备状态
     */
    @Override
    public Map<String, Boolean> getAllReadyStatus(String roomId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() == null) {
            return new HashMap<>();
        }
        return new HashMap<>(seats.getReadyByUserId());
    }

    /**
     * 重置所有玩家的准备状态
     */
    @Override
    public void resetAllReady(String roomId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() != null) {
            seats.getReadyByUserId().replaceAll((k, v) -> false);
            roomRepo.saveSeats(roomId, seats, ROOM_TTL);
        }
    }

    /**
     * 开始游戏（房主操作，需所有玩家准备）
     */
    @Override
    public void startGame(String roomId, String userId) {
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        
        // 检查是否是房主
        if (!userId.equals(meta.getOwnerUserId())) {
            throw new IllegalStateException("只有房主可以开始游戏");
        }
        
        // 检查房间状态
        RoomPhase currentPhase = getRoomPhase(roomId);
        if (currentPhase != RoomPhase.WAITING) {
            throw new IllegalStateException("房间状态不是WAITING，无法开始游戏");
        }
        
        // 检查准备状态
        Mode mode = Mode.valueOf(meta.getMode());
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        Map<String, Boolean> readyStatus = getAllReadyStatus(roomId);
        
        if (mode == Mode.PVE) {
            // PVE模式：AI默认已准备，只需要房主准备即可
            if (!readyStatus.getOrDefault(userId, false)) {
                throw new IllegalStateException("请先准备");
            }
        } else {
            // PVP模式：需要所有真人玩家都准备
            // 获取所有在房间内的玩家（通过seatXSessionId和seatOSessionId）
            Set<String> playersInRoom = new HashSet<>();
            if (seats.getSeatXSessionId() != null && !seats.getSeatXSessionId().isBlank()) {
                playersInRoom.add(seats.getSeatXSessionId());
            }
            if (seats.getSeatOSessionId() != null && !seats.getSeatOSessionId().isBlank()) {
                playersInRoom.add(seats.getSeatOSessionId());
            }
            
            // 检查是否有至少2个玩家
            if (playersInRoom.size() < 2) {
                throw new IllegalStateException("需要至少2名玩家才能开始游戏");
            }
            
            // 检查是否所有玩家都已准备
            for (String playerId : playersInRoom) {
                if (!readyStatus.getOrDefault(playerId, false)) {
                    throw new IllegalStateException("还有玩家未准备");
                }
            }
        }
        
        // 若当前盘已经结束，则在系列中开启下一盘（新 gameId / index）
        GomokuState currentState = getState(roomId);
        if (currentState.over()) {
            newGame(roomId);
        }

        // 切换房间状态为PLAYING
        setRoomPhase(roomId, RoomPhase.PLAYING);
    }

    /**
     * 获取房间状态（WAITING/PLAYING）
     */
    @Override
    public RoomPhase getRoomPhase(String roomId) {
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        String phaseStr = meta.getPhase();
        if (phaseStr == null || phaseStr.isBlank()) {
            return RoomPhase.WAITING; // 默认WAITING
        }
        try {
            return RoomPhase.valueOf(phaseStr);
        } catch (IllegalArgumentException e) {
            return RoomPhase.WAITING; // 无效值默认WAITING
        }
    }

    /**
     * 设置房间状态
     */
    @Override
    public void setRoomPhase(String roomId, RoomPhase phase) {
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        meta.setPhase(phase.name());
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
    }

    /**
     * 判断用户是否在房间内
     */
    @Override
    public boolean isUserInRoom(String roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        SeatsBinding seats = roomRepo.getSeats(roomId).orElse(null);
        if (seats == null) {
            return false;
        }
        if (userId.equals(seats.getSeatXSessionId()) || userId.equals(seats.getSeatOSessionId())) {
            return true;
        }
        Map<String, String> seatBySession = seats.getSeatBySession();
        return seatBySession != null && seatBySession.containsKey(userId);
    }

}
