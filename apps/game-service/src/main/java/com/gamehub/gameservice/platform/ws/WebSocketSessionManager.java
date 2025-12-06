package com.gamehub.gameservice.platform.ws;

import com.gamehub.gameservice.games.gomoku.domain.model.GomokuSnapshot;
import com.gamehub.gameservice.games.gomoku.interfaces.ws.dto.GomokuMessages;
import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.WebSocketSessionInfo;
import com.gamehub.gameservice.games.gomoku.domain.repository.RoomRepository;
import com.gamehub.gameservice.games.gomoku.infrastructure.redis.RedisKeys;
import com.gamehub.gameservice.games.gomoku.service.GomokuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Set;

/**
 * 监听 STOMP 连接/断开事件，结合 session-common 实现 WS 单点登录与踢旧。
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    /** 会话注册表，用于管理 WebSocket 会话（单点登录、踢旧连接） */
    private final SessionRegistry sessionRegistry;
    
    /** WebSocket 断连工具类，提供统一的断连方法 */
    private final WebSocketDisconnectHelper disconnectHelper;

    /** 房间仓储，用于查询玩家所在房间 */
    private final RoomRepository roomRepository;

    /** 游戏服务，用于广播房间快照 */
    private final GomokuService gomokuService;

    /** 消息模板，用于广播到房间 */
    private final SimpMessagingTemplate messagingTemplate;

    /** Redis模板，用于查询房间索引 */
    private final RedisTemplate<String, Object> redisTemplate;

    public WebSocketSessionManager(SessionRegistry sessionRegistry,
                                   WebSocketDisconnectHelper disconnectHelper,
                                   RoomRepository roomRepository,
                                   GomokuService gomokuService,
                                   SimpMessagingTemplate messagingTemplate,
                                   RedisTemplate<String, Object> redisTemplate) {
        this.sessionRegistry = sessionRegistry;
        this.disconnectHelper = disconnectHelper;
        this.roomRepository = roomRepository;
        this.gomokuService = gomokuService;
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 连接建立后登记会话，并踢下线旧连接。
     * 
     * 重要：从 JWT 中提取 loginSessionId，支持基于 loginSessionId 的会话管理。
     */
    @EventListener
    public void handleSessionConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal == null) {
            log.warn("收到 SessionConnectEvent 但缺少用户信息，session={}", accessor.getSessionId());
            return; // 未认证用户忽略
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            log.warn("SessionConnectEvent 缺少 sessionId，user={}", principal.getName());
            return;
        }
        String userId = principal.getName();
        
        // 从 JWT 中提取 loginSessionId（如果 Principal 是 JwtAuthenticationToken）
        String loginSessionId = extractLoginSessionId(principal);
        
        WebSocketSessionInfo info = WebSocketSessionInfo.builder()
                .sessionId(sessionId)
                .userId(userId)
                .loginSessionId(loginSessionId) // 可能为 null（向后兼容）
                .service("game-service")
                .build();
        //注册 WebSocket 会话,清理旧ws会话并返回旧ws会话集合
        List<WebSocketSessionInfo> kicked = sessionRegistry.registerWebSocketSessionEnforceSingle(info, 0);
        if (!CollectionUtils.isEmpty(kicked)) {
            log.info("用户 {} WebSocket 单点登录，新连接 {} 踢掉旧连接 {} 个, loginSessionId={}", 
                    userId, sessionId, kicked.size(), loginSessionId);
            kicked.forEach(old -> {
                disconnectHelper.sendKickMessage(userId, old.getSessionId(), "账号已在其他终端登录");
                disconnectHelper.forceDisconnect(old.getSessionId());
            });
        } else {
            log.info("用户 {} WebSocket 连接 {} 注册完成，无旧连接, loginSessionId={}",
                    userId, sessionId, loginSessionId);
        }
    }
    
    /**
     * 从 Principal 中提取 loginSessionId。
     * 
     * 如果 Principal 是 JwtAuthenticationToken，从 JWT claim 中提取 sid（loginSessionId）。
     * 
     * @param principal 用户 Principal
     * @return loginSessionId，如果无法提取则返回 null
     */
    private String extractLoginSessionId(Principal principal) {
        if (principal instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            // 优先使用 sid
            Object sidObj = jwt.getClaim("sid");
            if (sidObj != null) {
                String sid = sidObj.toString();
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
            }
            // 如果没有 sid，尝试使用 session_state（向后兼容）
            Object sessionStateObj = jwt.getClaim("session_state");
            if (sessionStateObj != null) {
                String sessionState = sessionStateObj.toString();
                if (sessionState != null && !sessionState.isBlank()) {
                    return sessionState;
                }
            }
        }
        return null;
    }

    /**
     * 连接断开时清理会话。
     * 
     * 重要：此方法会检测到所有类型的断开，包括：
     * - 正常关闭浏览器/页签
     * - 强制关闭浏览器
     * - 网络中断
     * - 系统崩溃
     * 
     * 因为这是基于 TCP 连接断开的检测，比浏览器端事件更可靠。
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            log.warn("【WebSocket断开检测】收到 SessionDisconnectEvent 但缺少 sessionId");
            return;
        }
        
        // 1. 从 SessionRegistry 查询会话信息（包含 userId）
        WebSocketSessionInfo sessionInfo = sessionRegistry.getWebSocketSession(sessionId);
        String userId = null;
        String loginSessionId = null;
        
        if (sessionInfo != null) {
            userId = sessionInfo.getUserId();
            loginSessionId = sessionInfo.getLoginSessionId();
            log.info("【WebSocket断开检测】检测到断开: sessionId={}, userId={}, loginSessionId={}, service={}, connectedAt={}", 
                    sessionId, userId, loginSessionId, sessionInfo.getService(), 
                    sessionInfo.getConnectedAt() != null ? 
                        java.time.Instant.ofEpochMilli(sessionInfo.getConnectedAt()) : null);
        } else {
            // 如果无法从 SessionRegistry 获取，尝试从事件中提取
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
            Principal principal = accessor.getUser();
            if (principal != null) {
                userId = principal.getName();
                loginSessionId = extractLoginSessionId(principal);
                log.info("【WebSocket断开检测】检测到断开（从事件提取）: sessionId={}, userId={}, loginSessionId={}", 
                        sessionId, userId, loginSessionId);
            } else {
                log.warn("【WebSocket断开检测】检测到断开但无法获取用户信息: sessionId={}", sessionId);
            }
        }
        
        // 2. 先清理 WebSocket 会话注册（确保后续查询连接状态时不会误判为在线）
        sessionRegistry.unregisterWebSocketSession(sessionId);
        log.debug("【WebSocket断开检测】已清理会话注册: sessionId={}", sessionId);
        
        // 3. 查询玩家所在房间并广播连接状态变化（在清理会话之后，确保快照中的连接状态正确）
        if (userId != null) {
            log.info("【WebSocket断开检测】玩家断开连接详情: userId={}, sessionId={}, loginSessionId={}, 断开时间={}", 
                    userId, sessionId, loginSessionId, java.time.Instant.now());
            
            // 查询玩家所在房间并广播连接状态变化
            String roomId = findRoomByUserId(userId);
            if (roomId != null) {
                log.info("【WebSocket断开检测】玩家所在房间: userId={}, roomId={}", userId, roomId);
                // 广播房间快照，让房间内其他在线玩家知道该玩家已断开
                // 注意：此时 SessionRegistry 已清理，snapshot() 查询连接状态时会正确返回"离线"
                try {
                    GomokuSnapshot snap = gomokuService.snapshot(roomId);
                    GomokuMessages.BroadcastEvent evt =
                            new GomokuMessages.BroadcastEvent();
                    evt.setRoomId(roomId);
                    evt.setType("SNAPSHOT");
                    evt.setPayload(snap);
                    messagingTemplate.convertAndSend("/topic/room." + roomId, evt);
                    log.debug("【WebSocket断开检测】已广播房间快照: roomId={}, userId={}", roomId, userId);
                } catch (Exception e) {
                    log.warn("【WebSocket断开检测】广播房间快照失败: roomId={}, userId={}", roomId, userId, e);
                }
            }
        }
    }

    /**
     * 根据userId查询玩家所在房间
     * 遍历房间索引，检查每个房间的SeatsBinding
     */
    private String findRoomByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            // 获取所有房间ID（从房间索引ZSET）
            Set<Object> roomIds = redisTemplate.opsForZSet()
                    .range(RedisKeys.roomIndexKey(), 0, -1);
            
            if (roomIds == null || roomIds.isEmpty()) {
                return null;
            }
            
            // 遍历房间，查找包含该userId的房间
            for (Object roomIdObj : roomIds) {
                String roomId = String.valueOf(roomIdObj);
                try {
                    var seatsOpt = roomRepository.getSeats(roomId);
                    if (seatsOpt.isPresent()) {
                        var seats = seatsOpt.get();
                        if (userId.equals(seats.getSeatXSessionId()) || userId.equals(seats.getSeatOSessionId())) {
                            return roomId;
                        }
                    }
                } catch (Exception e) {
                    // 忽略单个房间查询失败，继续查找
                    log.debug("查询房间座位信息失败: roomId={}", roomId, e);
                }
            }
        } catch (Exception e) {
            log.warn("查询玩家所在房间失败: userId={}", userId, e);
        }
        return null;
    }

}
