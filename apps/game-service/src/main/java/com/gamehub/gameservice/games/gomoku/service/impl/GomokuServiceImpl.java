package com.gamehub.gameservice.games.gomoku.service.impl;

import com.gamehub.gameservice.application.user.UserDirectoryService;
import com.gamehub.gameservice.application.user.UserProfileView;
import com.gamehub.gameservice.games.gomoku.domain.ai.GomokuAI;
import com.gamehub.gameservice.games.gomoku.domain.dto.GameStateRecord;
import com.gamehub.gameservice.games.gomoku.domain.dto.RoomMeta;
import com.gamehub.gameservice.games.gomoku.domain.dto.RoomMetaConverter;
import com.gamehub.gameservice.games.gomoku.domain.dto.SeatsBinding;
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
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Service
@RequiredArgsConstructor
public class GomokuServiceImpl implements GomokuService {

    private static final Duration ROOM_TTL = Duration.ofHours(48);


    // ====== 内存房间表 ======
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    // --- 强随机 & Base64URL（seatKey）
    private final SecureRandom seatKeyRnd = new SecureRandom();


    // ====== 新增：Redis 仓储 ======
    private final RoomRepository roomRepo;
    private final GameStateRepository gameRepo;
    private final TurnRepository turnRepo;
    private final UserDirectoryService userDirectoryService;
    private ObjectProvider<TurnClockCoordinator> coordinatorProvider;

    @Autowired
    public void setCoordinatorProvider(ObjectProvider<TurnClockCoordinator> coordinatorProvider) {
        this.coordinatorProvider = coordinatorProvider;
    }

    /** 每回合默认倒计时时长（秒） */
    @Value("${gomoku.turn.seconds:30}")
    private int turnSeconds;

    private final OngoingGameTracker ongoingGameTracker;


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

        // 6) 内存快照：为现有控制器保留
        rooms.put(roomId, new Room(
                roomId, m, ru, (m == Mode.PVE ? ai : 0),
                new GomokuAI(3, ru == Rule.RENJU),gameId
        ));

        return roomId;
    }

    private Room room(String roomId) {
        // 1) 内存命中
        Room cached = rooms.get(roomId);
        if (cached != null) return cached;

        // 2) 取房间元信息（模式/规则/AI方/当前盘 gameId、比分、index）
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));

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

    @Override
    public GomokuState place(String roomId, int x, int y, char piece) {
        Room r = room(roomId);
        
        // 检查房间状态：WAITING状态下不允许落子
        RoomPhase phase = getRoomPhase(roomId);
        if (phase == RoomPhase.WAITING) {
            throw new IllegalStateException("游戏尚未开始，请先双方准备并由房主开始游戏");
        }
        
        GomokuState s = r.getSeries().getCurrent().getState();  // ✅ 必须落到"当前盘"

        //已结束局直接返回
        if (s.over()) return s;
        //回合锁：必须是当前执子
        if (s.current() != piece) {
            throw new IllegalStateException("未轮到该方走棋（当前应为 " + s.current() + "）");
        }
        //合法性：坐标合法 & 未被占用
        if (!GomokuJudge.isLegal(s.board(), x, y)) {
            throw new IllegalArgumentException("落点非法或已占用");
        }
        // 【已移除】边界限制已移除，允许在 0-14 的所有交叉点落子
        // 15x15 棋盘有 15 个交叉点（0-14），都可以落子
        // 黑方禁手（仅在 RENJU 模式判断）
        if (r.getRule() == Rule.RENJU && piece == Board.BLACK
                && GomokuJudgeRenju.isForbiddenMove(s.board(), x, y)) {
            throw new IllegalArgumentException("黑方禁手（长连 / 四四 / 三三）");
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

    @Override
    public Move suggest(String roomId, char side) {
        Room r = room(roomId);
        return r.getAi().bestMove(r.getSeries().getCurrent().getState().board(), side); // 建议同样遵循禁手与威胁优先
    }

    /**
     * 重开游戏
     * @param roomId
     * @return
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

    @Override
    public GomokuState getState(String roomId) {
        // 若 GomokuState 可变，推荐返回副本；否则可直接返回
        return getRoomOrThrow(roomId).getSeries().getCurrent().getState();
    }


    // 3) 一站式：必要时自动让 AI 走一步
    // 此方法没验证，需要改
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
     * 对控制器暴露只读方法
     * @param roomId
     * @return
     */
    @Override
    public Mode getMode(String roomId) {
        Room r = room(roomId);
        if (r == null) throw new IllegalArgumentException("ROOM_NOT_FOUND");
        return r.getMode();
    }

    @Override
    public char getAiPiece(String roomId) {
        Room r = room(roomId);
        if (r == null) throw new IllegalArgumentException("ROOM_NOT_FOUND");
        return r.getAiPiece();
    }

    /**
     * 为当前会话分配或恢复执子方（X/O），并在房间内绑定。
     * 逻辑：
     * 1. 若该 session 已占座 → 直接返回；
     * 2. 若 wantSide 明确且未被占 → 分配给当前会话；
     * 3. PVE 模式：只给非 AI 的一方；
     * 4. PVP 模式：优先给想要的，否则分配空位；
     * 5. 两边都被占 → 抛异常。
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
            // PVP：若用户有意向且空位则尊重；否则自动分配
            char want = (wantSide == null ? 0 : Character.toUpperCase(wantSide));
            
            if (want == Board.BLACK) {
                // 用户想要黑方
                if (curX == null || curX.isBlank()) {
                    // 黑方空闲，可以绑定
                    side = Board.BLACK;
                } else if (curX.equals(userId)) {
                    // 已经是自己占用的，直接返回
                    side = Board.BLACK;
                } else {
                    // 黑方已被其他人占用，拒绝绑定
                    throw new IllegalStateException("黑方座位已被占用");
                }
            } else if (want == Board.WHITE) {
                // 用户想要白方
                if (curO == null || curO.isBlank()) {
                    // 白方空闲，可以绑定
                    side = Board.WHITE;
                } else if (curO.equals(userId)) {
                    // 已经是自己占用的，直接返回
                    side = Board.WHITE;
                } else {
                    // 白方已被其他人占用，拒绝绑定
                    throw new IllegalStateException("白方座位已被占用");
                }
            } else {
                // 用户没有指定意向，自动分配空位
                if (curX == null || curX.isBlank()) {
                    side = Board.BLACK;
                } else if (curO == null || curO.isBlank()) {
                    side = Board.WHITE;
                } else {
                    throw new IllegalStateException("房间已满");
                }
            }
        }

        // 3) 回写 Redis 绑定（同 sessionId 覆盖）
        if (side == Board.BLACK) {
            seats.setSeatXSessionId(userId);
        } else {
            seats.setSeatOSessionId(userId);
        }
        seats.getSeatBySession().put(userId, String.valueOf(side));
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);

        // 4) 同步到内存快照（兼容现有逻辑）
        r.getSeatBySession().put(userId, side);
        if (side == Board.BLACK) r.setSeatXSessionId(userId);
        else r.setSeatOSessionId(userId);

        return side;
    }

    @Override
    public String getGameId(String roomId) {
        return room(roomId).getSeries().getCurrent().getGameId();
    }

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
     * 新建游戏
     * @param roomId
     * @return
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
     * 超时/认输
     * @param roomId
     * @param side
     * @return
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

        // 查询两侧玩家的详细信息（昵称、头像、战绩等）
        UserProfileView seatXUserInfo = null;
        UserProfileView seatOUserInfo = null;
        java.util.List<String> idsToQuery = new java.util.ArrayList<>();
        if (seatXUserId != null && !seatXUserId.isBlank()) {
            idsToQuery.add(seatXUserId);
        }
        if (seatOUserId != null && !seatOUserId.isBlank()
                && !idsToQuery.contains(seatOUserId)) {
            idsToQuery.add(seatOUserId);
        }
        if (!idsToQuery.isEmpty()) {
            java.util.List<UserProfileView> profiles = userDirectoryService.getUserInfos(idsToQuery);
            for (UserProfileView p : profiles) {
                if (p == null || p.getUserId() == null) continue;
                if (p.getUserId().equals(seatXUserId)) {
                    seatXUserInfo = p;
                } else if (p.getUserId().equals(seatOUserId)) {
                    seatOUserInfo = p;
                }
            }
        }

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
                readyStatus
        );
    }

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

        com.gamehub.gameservice.games.gomoku.domain.dto.TurnAnchor anchor = turnRepo.get(roomId).orElse(null);
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
    @Override
    public LeaveResult leaveRoom(String roomId, String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));

        Mode mode = Mode.valueOf(meta.getMode());
        if (mode == Mode.PVE) {
            destroyRoom(roomId);
            ongoingGameTracker.clear(userId);
            return new LeaveResult(true, null, null);
        }

        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        boolean isX = userId.equals(seats.getSeatXSessionId());
        boolean isO = userId.equals(seats.getSeatOSessionId());

        if (!isX && !isO) {
            ongoingGameTracker.clear(userId);
            return new LeaveResult(false, meta.getOwnerUserId(), null);
        }

        String opponentUserId = isX ? seats.getSeatOSessionId() : seats.getSeatXSessionId();
        boolean opponentPresent = opponentUserId != null && !opponentUserId.isBlank();
        char freedSeat = isX ? Board.BLACK : Board.WHITE;

        if (!opponentPresent) {
            destroyRoom(roomId);
            ongoingGameTracker.clear(userId);
            return new LeaveResult(true, null, freedSeat);
        }

        if (isX) {
            seats.setSeatXSessionId(null);
        } else {
            seats.setSeatOSessionId(null);
        }
        if (seats.getSeatBySession() != null) {
            seats.getSeatBySession().remove(userId);
        }
        roomRepo.saveSeats(roomId, seats, ROOM_TTL);

        Room local = rooms.get(roomId);
        if (local != null) {
            local.getSeatBySession().remove(userId);
            if (isX) {
                local.setSeatXSessionId(null);
            } else {
                local.setSeatOSessionId(null);
            }
        }

        String newOwner = meta.getOwnerUserId();
        if (userId.equals(meta.getOwnerUserId())) {
            newOwner = opponentUserId;
            meta.setOwnerUserId(newOwner);
            roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
        }

        // 重置所有玩家的准备状态（体验优化：避免新玩家进入后立即开始）
        resetAllReady(roomId);
        
        // 如果房间状态是PLAYING，切换回WAITING
        RoomPhase currentPhase = getRoomPhase(roomId);
        if (currentPhase == RoomPhase.PLAYING) {
            setRoomPhase(roomId, RoomPhase.WAITING);
        }

        ongoingGameTracker.clear(userId);
        return new LeaveResult(false, newOwner, freedSeat);
    }




    // 放在类里其他方法附近即可 —— 私有工具：取房间或抛错
    private Room getRoomOrThrow(String roomId) {
        Room r = room(roomId); // ← 你已有的 Map<String, Room> rooms
        if (r == null) throw new IllegalArgumentException("Room not found: " + roomId);
        return r;
    }

    private void destroyRoom(String roomId) {
        rooms.remove(roomId);
        roomRepo.deleteRoom(roomId);
        roomRepo.deleteSeats(roomId);
        roomRepo.deleteSeatKeys(roomId);
        roomRepo.deleteSeries(roomId);
        gameRepo.deleteAll(roomId);
        turnRepo.delete(roomId);
        stopClock(roomId);
    }

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

    @Override
    public boolean getReadyStatus(String roomId, String userId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() == null) {
            return false;
        }
        return seats.getReadyByUserId().getOrDefault(userId, false);
    }

    @Override
    public Map<String, Boolean> getAllReadyStatus(String roomId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() == null) {
            return new HashMap<>();
        }
        return new HashMap<>(seats.getReadyByUserId());
    }

    @Override
    public void resetAllReady(String roomId) {
        SeatsBinding seats = roomRepo.getSeats(roomId).orElseGet(SeatsBinding::new);
        if (seats.getReadyByUserId() != null) {
            seats.getReadyByUserId().replaceAll((k, v) -> false);
            roomRepo.saveSeats(roomId, seats, ROOM_TTL);
        }
    }

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

    @Override
    public void setRoomPhase(String roomId, RoomPhase phase) {
        RoomMeta meta = roomRepo.getRoomMeta(roomId)
                .orElseThrow(() -> new IllegalArgumentException("ROOM_NOT_FOUND: " + roomId));
        meta.setPhase(phase.name());
        roomRepo.saveRoomMeta(roomId, meta, ROOM_TTL);
    }

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
