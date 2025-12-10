package com.gamehub.gameservice.games.gomoku.interfaces.http;

import com.gamehub.gameservice.application.user.UserDirectoryService;
import com.gamehub.gameservice.application.user.UserProfileView;
import com.gamehub.gameservice.games.gomoku.domain.enums.Mode;
import com.gamehub.gameservice.games.gomoku.domain.enums.Rule;
import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.GomokuMessages;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import com.gamehub.gameservice.platform.ongoing.OngoingGameInfo;
import com.gamehub.gameservice.platform.ongoing.OngoingGameTracker;
import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 五子棋游戏http接口请求控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/gomoku")
public class GomokuRestController {

    private final GomokuService svc;
    private final OngoingGameTracker ongoingGameTracker;
    private final UserDirectoryService userDirectoryService;
    /** 用于从 HTTP 层主动广播房间 SNAPSHOT（例如加入/退出房间） */
    private final SimpMessagingTemplate messagingTemplate;

    public GomokuRestController(GomokuService svc,
                                 OngoingGameTracker ongoingGameTracker,
                                 UserDirectoryService userDirectoryService,
                                 SimpMessagingTemplate messagingTemplate) {
        this.svc = svc;
        this.ongoingGameTracker = ongoingGameTracker;
        this.userDirectoryService = userDirectoryService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 新建房间：
     *  mode=PVP 或 PVE（默认 PVE）
     *  aiPiece=X 或 O（PVE时有效，默认 O=后手）
     *  rule 禁手规则
     *  需要认证用户，创建者作为房主
     *  
     *  限制：如果玩家已有正在进行的游戏房间，不允许创建新房间
     */
    @PostMapping("/new")
    public ResponseEntity<ApiResponse<String>> newRoom(@RequestParam(name = "mode", defaultValue="PVE") String mode,
                                                       @RequestParam(name = "aiPiece", required = false) Character aiPiece,
                                                       @RequestParam(name = "rule", defaultValue="STANDARD") String rule,
                                                       @AuthenticationPrincipal Jwt jwt) {
        String ownerUserId = CurrentUserHelper.getUserId(jwt);
        
        // 检查玩家是否已有正在进行的游戏房间
        var ongoingOpt = ongoingGameTracker.find(ownerUserId);
        if (ongoingOpt.isPresent()) {
            return ResponseEntity.status(409)
                    .body(ApiResponse.conflict("您已有正在进行的游戏房间，请先完成或退出当前房间后再创建新房间"));
        }
        
        // 从数据库获取最新的用户信息（优先读缓存，没命中再 Feign 调用）
        // 复用 UserDirectoryService，避免重复代码
        UserProfileView ownerProfile = userDirectoryService.getUserInfo(ownerUserId);
        String ownerName;
        if (ownerProfile != null) {
            ownerName = ownerProfile.getDisplayName(); // 优先昵称，其次用户名，最后 userId
        } else {
            // 兜底：如果获取用户信息失败，使用 JWT 中的信息
            var user = CurrentUserHelper.from(jwt);
            ownerName = user != null ? user.getDisplayName() : ownerUserId;
            log.warn("获取房主用户信息失败，使用 JWT 中的信息: userId={}", ownerUserId);
        }
        
        var m = "PVP".equalsIgnoreCase(mode) ? Mode.PVP : Mode.PVE;
        var r = "RENJU".equalsIgnoreCase(rule) ? Rule.RENJU : Rule.STANDARD;
        String roomId = svc.newRoom(m, aiPiece, r, ownerUserId, ownerName);
        return ResponseEntity.ok(ApiResponse.success(roomId));
    }

    /**
     * 获取房间的“全量只读快照”（RoomView）
     * ----------------------------------------------------
     * 用途：
     *     页面首屏渲染：进入房间后的一次性全量拉取；
     *     调试排查：后端/运维查看当前房间的整体状态。
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
     * 加入房间：玩家加入其他玩家创建的房间
     * ----------------------------------------------------
     * 语义：
     * - 已经在房间内（任意座位）则返回 409，前端直接跳转进入房间；
     * - 如果玩家已有正在进行的其他游戏房间，不允许加入新房间；
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

        // 0.1 检查玩家是否已有正在进行的其他游戏房间
        var ongoingOpt = ongoingGameTracker.find(userId);
        if (ongoingOpt.isPresent()) {
            var ongoing = ongoingOpt.get();
            // 如果正在进行的房间不是当前要加入的房间，则拒绝
            if (!roomId.equals(ongoing.getRoomId())) {
                return ResponseEntity.status(409)
                        .body(ApiResponse.conflict("您已有正在进行的游戏房间，请先完成或退出当前房间后再加入其他房间"));
            }
            // 如果正在进行的房间就是当前房间，说明可能是状态不同步，允许继续
        }

        // 1. 绑定座位（自动分配）
        char side = svc.resolveAndBindSide(roomId, userId, null);

        // 1.1 缓存当前加入者的用户资料，后续 snapshot 直接读取
        svc.cacheUserProfile(roomId, userId);
        
        // 2. 记录用户正在进行中的房间（供前端"继续游戏"入口使用）
        ongoingGameTracker.save(userId, OngoingGameInfo.gomoku(roomId));

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
        // 如果房间未被销毁，广播一次 SNAPSHOT 更新剩余玩家
        if (!result.roomDestroyed()) {
            broadcastSnapshot(roomId);
        }
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
