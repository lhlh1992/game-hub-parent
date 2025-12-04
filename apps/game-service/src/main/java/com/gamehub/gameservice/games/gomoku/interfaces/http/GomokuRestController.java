package com.gamehub.gameservice.games.gomoku.interfaces.http;


import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuState;
import com.gamehub.gameservice.games.gomoku.domain.model.Move;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.GomokuMessages;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gomoku")
public class GomokuRestController {

    private final GomokuService svc;
    private final com.gamehub.gameservice.platform.ongoing.OngoingGameTracker ongoingGameTracker;
    /** 用于从 HTTP 层主动广播房间 SNAPSHOT（例如加入/退出房间） */
    private final SimpMessagingTemplate messagingTemplate;

    public GomokuRestController(GomokuService svc,
                                 com.gamehub.gameservice.platform.ongoing.OngoingGameTracker ongoingGameTracker,
                                 SimpMessagingTemplate messagingTemplate) {
        this.svc = svc;
        this.ongoingGameTracker = ongoingGameTracker;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 新建房间：
     *  mode=PVP 或 PVE（默认 PVE）
     *  aiPiece=X 或 O（PVE时有效，默认 O=后手）
     *  rule 禁手规则
     *  需要认证用户，创建者作为房主
     */
    @PostMapping("/new")
    public ResponseEntity<ApiResponse<String>> newRoom(@RequestParam(name = "mode", defaultValue="PVE") String mode,
                                                       @RequestParam(name = "aiPiece", required = false) Character aiPiece,
                                                       @RequestParam(name = "rule", defaultValue="STANDARD") String rule,
                                                       @AuthenticationPrincipal Jwt jwt) {
        var user = CurrentUserHelper.from(jwt);
        String ownerUserId = user.userId();
        String ownerName = user.getDisplayName(); // 使用统一的显示名称（nickname > username > userId）
        var m = "PVP".equalsIgnoreCase(mode) ? Mode.PVP : Mode.PVE;
        var r = "RENJU".equalsIgnoreCase(rule) ? Rule.RENJU : Rule.STANDARD;
        String roomId = svc.newRoom(m, aiPiece, r, ownerUserId, ownerName);
        return ResponseEntity.ok(ApiResponse.success(roomId));
    }

    /**
     * 获取房间的“全量只读快照”（RoomView）
     * ----------------------------------------------------
     * 用途：
     * <ul>
     *     <li>页面首屏渲染：进入房间后的一次性全量拉取；</li>
     *     <li>调试排查：后端/运维查看当前房间的整体状态。</li>
     * </ul>
     *
     * 说明：
     * - 返回值为 {@link GomokuSnapshot}，由服务层统一从 Redis 聚合生成；
     * - 不依赖内存 Room 对象，支持将来水平扩展/多节点部署。
     */
    @GetMapping("/rooms/{roomId}/view")
    public ResponseEntity<ApiResponse<GomokuSnapshot>> viewRoom(@PathVariable String roomId) {
        GomokuSnapshot snapshot = svc.snapshot(roomId);
        return ResponseEntity.ok(ApiResponse.success(snapshot));
    }

    /**
     * 玩家落子：x,y,piece=X/O；必要时会触发AI同步下子
     * 对外入参保持不变，但内部根据认证用户解析其实际执子，确保鉴权。
     */
    @PostMapping("/{roomId}/place")
    public ResponseEntity<GameStateDTO> place(@PathVariable String roomId,
                                              @RequestParam int x,
                                              @RequestParam int y,
                                              @RequestParam char piece,
                                              @AuthenticationPrincipal Jwt jwt) {
        // 兼容：仍接受 piece 作为"意向"，实际执子由服务基于 userId+房间分配校验
        String userId = CurrentUserHelper.getUserId(jwt);
        Character want = (piece == 'X' || piece == 'O') ? piece : null;
        char caller = svc.resolveAndBindSide(roomId, userId, want);
        GomokuState s = svc.place(roomId, x, y, caller);
        return ResponseEntity.ok(GameStateDTO.from(s));
    }

    /** AI 辅助建议：不自动下，只返回推荐点 */
    @GetMapping("/{roomId}/suggest")
    public ResponseEntity<MoveDTO> suggest(@PathVariable String roomId,
                                           @RequestParam(defaultValue="X") char side) {
        Move m = svc.suggest(roomId, side);
        return ResponseEntity.ok(new MoveDTO(m.x(), m.y(), m.piece()));
    }
    // -------- DTO --------
    public record MoveDTO(int x,int y,char piece){}
    public record GameStateDTO(char[][] board,char current,boolean over,Character winner){
        static GameStateDTO from(GomokuState s){ return new GameStateDTO(s.board().view(), s.current(), s.over(), s.winner()); }
    }

    /**
     * 加入房间：玩家加入其他玩家创建的房间
     * ----------------------------------------------------
     * 语义：
     * - 已经在房间内（任意座位）则返回 409，前端直接跳转进入房间；
     * - 否则自动为玩家分配一个座位并绑定到 Redis；
     * - 成功后通过 WebSocket 广播最新 SNAPSHOT，让房主等人立刻看到新玩家。
     * 
     * @param roomId 房间ID
     * @param jwt JWT token
     * @return 加入结果，包含分配的座位（'X' 或 'O'）
     */
    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<ApiResponse<JoinRoomResponse>> joinRoom(@PathVariable String roomId,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        String userId = CurrentUserHelper.getUserId(jwt);
        
        // 0. 判断是否已经在房间内（已绑定座位）
        if (svc.isUserInRoom(roomId, userId)) {
            return ResponseEntity.status(409)
                    .body(ApiResponse.conflict("您已经在该房间，请直接进入"));
        }

        // 1. 绑定座位（自动分配）
        char side = svc.resolveAndBindSide(roomId, userId, null);
        
        // 2. 记录用户正在进行中的房间（供前端"继续游戏"入口使用）
        ongoingGameTracker.save(userId, 
                com.gamehub.gameservice.platform.ongoing.OngoingGameInfo.gomoku(roomId));

        // 3. 广播一次最新房间快照（SNAPSHOT），告知房间内所有订阅者：
        //    - 座位已被占用
        //    - 准备状态/房间状态等如有变更一并更新
        broadcastSnapshot(roomId);

        return ResponseEntity.ok(ApiResponse.success(new JoinRoomResponse(side)));
    }
    
    /**
     * 房间内玩家主动退出
     */
    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<ApiResponse<GomokuService.LeaveResult>> leaveRoom(@PathVariable String roomId,
                                                                            @AuthenticationPrincipal Jwt jwt) {
        String userId = CurrentUserHelper.getUserId(jwt);
        var result = svc.leaveRoom(roomId, userId);
        // 离开房间同样可能改变座位占用/房主/房间状态，这里也广播一次 SNAPSHOT
        broadcastSnapshot(roomId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    // -------- DTO --------
    public record JoinRoomResponse(char side) {}

    /**
     * HTTP 控制器内部使用的工具方法：
     * 读取最新房间快照并以 SNAPSHOT 事件广播到对应的 WS 主题。
     *
     * 注意：
     * - 这里仅负责封装事件结构并调用 {@link SimpMessagingTemplate}；
     * - 具体快照内容全部由 {@link GomokuService#snapshot(String)} 提供。
     */
    private void broadcastSnapshot(String roomId) {
        GomokuSnapshot snap = svc.snapshot(roomId);
        GomokuMessages.BroadcastEvent evt = new GomokuMessages.BroadcastEvent();
        evt.setRoomId(roomId);
        evt.setType("SNAPSHOT");
        evt.setPayload(snap);
        messagingTemplate.convertAndSend("/topic/room." + roomId, evt);
    }
}
