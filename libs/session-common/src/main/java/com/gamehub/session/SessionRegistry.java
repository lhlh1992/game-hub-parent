package com.gamehub.session;

import com.alibaba.fastjson2.JSON;
import com.gamehub.session.config.SessionRedisConfig;
import com.gamehub.session.model.LoginSessionInfo;
import com.gamehub.session.model.SessionStatus;
import com.gamehub.session.model.UserSessionSnapshot;
import com.gamehub.session.model.WebSocketSessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一会话注册表。
 *
 * 职责：
 * - 记录/查询/清理“登录会话（JWT/Token）”。
 * - 记录/查询/清理“WebSocket 长连接会话”。
 * - 提供聚合视图与批量清理能力（用于后台“强制下线”）。
 *
 * 存储：全部基于 Redis，键空间如下（前缀见常量）：
 * - session:login:user:{userId}     -> Set<tokenId>
 * - session:login:token:{tokenId}   -> LoginSessionInfo JSON
 * - session:ws:user:{userId}        -> Set<sessionId>
 * - session:ws:session:{sessionId}  -> WebSocketSessionInfo JSON
 */
@Slf4j
public class SessionRegistry {

    /** Redis Key 前缀：某用户所持有的登录会话集合 */
    private static final String LOGIN_USER_KEY_PREFIX = "session:login:user:";
    /** Redis Key 前缀：登录会话详情（按 tokenId/sessionId 存储） */
    private static final String LOGIN_SESSION_KEY_PREFIX = "session:login:token:";
    /** Redis Key 前缀：登录会话详情（按 loginSessionId 存储，用于快速查询） */
    private static final String LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX = "session:login:loginSession:";
    /** Redis Key 前缀：某用户所持有的 WebSocket 会话集合 */
    private static final String WS_USER_KEY_PREFIX = "session:ws:user:";
    /** Redis Key 前缀：WebSocket 会话详情（按 sessionId 存储） */
    private static final String WS_SESSION_KEY_PREFIX = "session:ws:session:";

    /** 登录会话默认 TTL（12 小时） */
    private static final Duration DEFAULT_LOGIN_TTL = Duration.ofHours(12);
    /** WebSocket 会话默认 TTL（24 小时） */
    private static final Duration DEFAULT_WS_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redis;

    public SessionRegistry(@Qualifier(SessionRedisConfig.SESSION_REDIS_TEMPLATE_BEAN) RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    /* =========================
     * 登录会话（HTTP/JWT）管理
     * ========================= */

    /**
     * 注册或续期登录会话。
     * 
     * 存储策略：
     * - 按 sessionId（jti）存储：`session:login:token:{sessionId}` → LoginSessionInfo（向后兼容）
     * - 按 loginSessionId 存储：`session:login:loginSession:{loginSessionId}` → LoginSessionInfo（如果提供了 loginSessionId）
     * 
     * @param sessionInfo 登录会话信息
     * @param ttlSeconds  存活时间（秒），<=0 使用默认值
     */
    public void registerLoginSession(LoginSessionInfo sessionInfo, long ttlSeconds) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        requireText(sessionInfo.getSessionId(), "sessionId");
        requireText(sessionInfo.getUserId(), "userId");

        if (sessionInfo.getIssuedAt() == null) {
            sessionInfo.setIssuedAt(Instant.now().toEpochMilli());
        }
        if (sessionInfo.getAttributes() == null) {
            sessionInfo.setAttributes(new HashMap<>());
        }
        // 确保状态不为 null（向后兼容）
        if (sessionInfo.getStatus() == null) {
            sessionInfo.setStatus(SessionStatus.ACTIVE);
        }

        Duration ttl = resolveTtl(ttlSeconds, DEFAULT_LOGIN_TTL);
        String userKey = LOGIN_USER_KEY_PREFIX + sessionInfo.getUserId();
        String sessionKey = LOGIN_SESSION_KEY_PREFIX + sessionInfo.getSessionId();
        String sessionJson = JSON.toJSONString(sessionInfo);

        // 1. 按 sessionId 存储（向后兼容）
        redis.opsForSet().add(userKey, sessionInfo.getSessionId());
        redis.opsForValue().set(sessionKey, sessionJson, ttl);
        
        // 2. 如果提供了 loginSessionId，同时按 loginSessionId 存储（用于快速查询）
        if (sessionInfo.getLoginSessionId() != null && !sessionInfo.getLoginSessionId().isBlank()) {
            String loginSessionKey = LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + sessionInfo.getLoginSessionId();
            redis.opsForValue().set(loginSessionKey, sessionJson, ttl);
            log.debug("注册登录会话: userId={}, sessionId={}, loginSessionId={}, status={}, ttl={}s", 
                    sessionInfo.getUserId(), sessionInfo.getSessionId(), sessionInfo.getLoginSessionId(), 
                    sessionInfo.getStatus(), ttl.getSeconds());
        } else {
            log.debug("注册登录会话: userId={}, sessionId={}, status={}, ttl={}s", 
                    sessionInfo.getUserId(), sessionInfo.getSessionId(), sessionInfo.getStatus(), ttl.getSeconds());
        }
    }

    /**
     * 【单点登录】注册登录会话：在登记新会话前，将该用户的所有 ACTIVE 会话标记为 KICKED（返回被踢下线的旧会话）。
     * 
     * 重要变更（步骤2）：
     * - 不再删除旧会话，而是标记为 KICKED，保留审计记录
     * - 新会话状态自动设置为 ACTIVE
     * 
     * @param sessionInfo 新登录会话信息
     * @param ttlSeconds  存活时间（秒），<=0 使用默认值
     * @return 被踢下线的旧会话列表（状态已更新为 KICKED）
     */
    public List<LoginSessionInfo> registerLoginSessionEnforceSingle(LoginSessionInfo sessionInfo, long ttlSeconds) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        requireText(sessionInfo.getUserId(), "userId");
        
        // 1) 获取该用户的所有 ACTIVE 会话
        List<LoginSessionInfo> activeSessions = getActiveLoginSessions(sessionInfo.getUserId());
        
        // 2) 将旧会话标记为 KICKED（不再删除）
        List<LoginSessionInfo> kicked = new ArrayList<>();
        for (LoginSessionInfo oldSession : activeSessions) {
            // 跳过自己（如果新会话的 loginSessionId 与旧会话相同，说明是同一登录会话的 token 刷新）
            if (sessionInfo.getLoginSessionId() != null 
                    && sessionInfo.getLoginSessionId().equals(oldSession.getLoginSessionId())) {
                log.debug("跳过同一 loginSessionId 的会话: loginSessionId={}", sessionInfo.getLoginSessionId());
                continue;
            }
            
            // 更新状态为 KICKED
            updateSessionStatus(oldSession.getSessionId(), SessionStatus.KICKED);
            oldSession.setStatus(SessionStatus.KICKED);
            kicked.add(oldSession);
            log.debug("标记会话为 KICKED: userId={}, sessionId={}, loginSessionId={}", 
                    oldSession.getUserId(), oldSession.getSessionId(), oldSession.getLoginSessionId());
        }
        
        // 3) 确保新会话状态为 ACTIVE
        sessionInfo.setStatus(SessionStatus.ACTIVE);
        
        // 4) 登记当前会话
        registerLoginSession(sessionInfo, ttlSeconds);
        
        return kicked;
    }

    /**
     * 注销单个登录会话。
     * 
     * 注意：此方法会删除会话记录。如果希望保留审计记录，可以使用 {@link #updateSessionStatus(String, SessionStatus)} 将状态设置为 EXPIRED。
     */
    public void unregisterLoginSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        LoginSessionInfo info = getLoginSession(sessionId);
        if (info != null) {
            redis.opsForSet().remove(LOGIN_USER_KEY_PREFIX + info.getUserId(), sessionId);
            
            // 如果存在 loginSessionId，同时删除按 loginSessionId 存储的数据
            if (info.getLoginSessionId() != null && !info.getLoginSessionId().isBlank()) {
                redis.delete(LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + info.getLoginSessionId());
            }
        }
        redis.delete(LOGIN_SESSION_KEY_PREFIX + sessionId);
        log.debug("注销登录会话: sessionId={}", sessionId);
    }

    /**
     * 查询用户的所有登录会话（包括所有状态：ACTIVE、KICKED、EXPIRED）。
     */
    public List<LoginSessionInfo> getLoginSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> sessionIds = redis.opsForSet().members(LOGIN_USER_KEY_PREFIX + userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<LoginSessionInfo> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            LoginSessionInfo info = getLoginSession(sessionId);
            if (info != null) {
                // 向后兼容：如果状态为 null，默认为 ACTIVE
                if (info.getStatus() == null) {
                    info.setStatus(SessionStatus.ACTIVE);
                }
                result.add(info);
            } else {
                // 清理脏数据
                redis.opsForSet().remove(LOGIN_USER_KEY_PREFIX + userId, sessionId);
            }
        }
        return result;
    }

    /**
     * 获取单个登录会话详情（按 sessionId 查询）。
     */
    public LoginSessionInfo getLoginSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().get(LOGIN_SESSION_KEY_PREFIX + sessionId);
        if (json == null) {
            return null;
        }
        try {
            LoginSessionInfo info = JSON.parseObject(json, LoginSessionInfo.class);
            // 向后兼容：如果状态为 null，默认为 ACTIVE
            if (info != null && info.getStatus() == null) {
                info.setStatus(SessionStatus.ACTIVE);
            }
            return info;
        } catch (Exception ex) {
            log.warn("反序列化 LoginSessionInfo 失败: sessionId={}", sessionId, ex);
            return null;
        }
    }

    /**
     * 根据 loginSessionId 查询登录会话详情。
     * 
     * @param loginSessionId 登录会话 ID（如 Keycloak 的 sid）
     * @return 登录会话信息，如果不存在则返回 null
     */
    public LoginSessionInfo getLoginSessionByLoginSessionId(String loginSessionId) {
        if (loginSessionId == null || loginSessionId.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().get(LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + loginSessionId);
        if (json == null) {
            return null;
        }
        try {
            LoginSessionInfo info = JSON.parseObject(json, LoginSessionInfo.class);
            // 向后兼容：如果状态为 null，默认为 ACTIVE
            if (info != null && info.getStatus() == null) {
                info.setStatus(SessionStatus.ACTIVE);
            }
            return info;
        } catch (Exception ex) {
            log.warn("反序列化 LoginSessionInfo 失败: loginSessionId={}", loginSessionId, ex);
            return null;
        }
    }

    /**
     * 获取用户的所有 ACTIVE 登录会话。
     * 
     * @param userId 用户 ID
     * @return ACTIVE 状态的登录会话列表
     */
    public List<LoginSessionInfo> getActiveLoginSessions(String userId) {
        List<LoginSessionInfo> allSessions = getLoginSessions(userId);
        return allSessions.stream()
                .filter(session -> session.getStatus() == SessionStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户的单个 ACTIVE 登录会话（如果存在多个，返回第一个）。
     * 
     * @param userId 用户 ID
     * @return ACTIVE 状态的登录会话，如果不存在则返回 null
     */
    public LoginSessionInfo getActiveLoginSession(String userId) {
        List<LoginSessionInfo> activeSessions = getActiveLoginSessions(userId);
        return activeSessions.isEmpty() ? null : activeSessions.get(0);
    }

    /**
     * 更新登录会话的状态。
     * 
     * @param sessionId 会话 ID（sessionId，即 jti）
     * @param status    新状态
     */
    public void updateSessionStatus(String sessionId, SessionStatus status) {
        if (sessionId == null || sessionId.isBlank() || status == null) {
            return;
        }
        
        LoginSessionInfo session = getLoginSession(sessionId);
        if (session == null) {
            log.warn("更新会话状态失败：会话不存在: sessionId={}", sessionId);
            return;
        }
        
        // 更新状态
        session.setStatus(status);
        String sessionJson = JSON.toJSONString(session);
        
        // 计算剩余 TTL
        Duration ttl = DEFAULT_LOGIN_TTL;
        if (session.getExpiresAt() != null && session.getExpiresAt() > 0) {
            long remainingSeconds = (session.getExpiresAt() - Instant.now().toEpochMilli()) / 1000;
            if (remainingSeconds > 0) {
                ttl = Duration.ofSeconds(remainingSeconds);
            }
        }
        
        // 更新按 sessionId 存储的数据
        String sessionKey = LOGIN_SESSION_KEY_PREFIX + sessionId;
        redis.opsForValue().set(sessionKey, sessionJson, ttl);
        
        // 如果存在 loginSessionId，同时更新按 loginSessionId 存储的数据
        if (session.getLoginSessionId() != null && !session.getLoginSessionId().isBlank()) {
            String loginSessionKey = LOGIN_SESSION_BY_LOGIN_SESSION_ID_PREFIX + session.getLoginSessionId();
            redis.opsForValue().set(loginSessionKey, sessionJson, ttl);
            log.info("【会话状态更新】已更新按 loginSessionId 索引的数据: loginSessionId={}, status={}, sessionId={}", 
                    session.getLoginSessionId(), status, sessionId);
        } else {
            log.warn("【会话状态更新】会话没有 loginSessionId，无法更新按 loginSessionId 索引的数据: sessionId={}, status={}", 
                    sessionId, status);
        }
        
        log.info("【会话状态更新】更新会话状态: sessionId={}, loginSessionId={}, status={}", 
                sessionId, session.getLoginSessionId(), status);
    }

    /**
     * 清理并返回该用户的所有登录会话。
     */
    public List<LoginSessionInfo> removeAllLoginSessions(String userId) {
        List<LoginSessionInfo> sessions = getLoginSessions(userId);
        for (LoginSessionInfo session : sessions) {
            redis.opsForSet().remove(LOGIN_USER_KEY_PREFIX + userId, session.getSessionId());
            redis.delete(LOGIN_SESSION_KEY_PREFIX + session.getSessionId());
        }
        return sessions;
    }

    /* ============================
     * WebSocket 会话管理
     * ============================ */

    /**
     * 注册或续期 WebSocket 会话。
     */
    public void registerWebSocketSession(WebSocketSessionInfo sessionInfo, long ttlSeconds) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        requireText(sessionInfo.getSessionId(), "sessionId");
        requireText(sessionInfo.getUserId(), "userId");
        requireText(sessionInfo.getService(), "service");

        if (sessionInfo.getConnectedAt() == null) {
            sessionInfo.setConnectedAt(Instant.now().toEpochMilli());
        }
        if (sessionInfo.getAttributes() == null) {
            sessionInfo.setAttributes(new HashMap<>());
        }

        Duration ttl = resolveTtl(ttlSeconds, DEFAULT_WS_TTL);
        String userKey = WS_USER_KEY_PREFIX + sessionInfo.getUserId();
        String sessionKey = WS_SESSION_KEY_PREFIX + sessionInfo.getSessionId();

        redis.opsForSet().add(userKey, sessionInfo.getSessionId());
        redis.opsForValue().set(sessionKey, JSON.toJSONString(sessionInfo), ttl);
        log.debug("注册 WS 会话: userId={}, sessionId={}, service={}, ttl={}s", sessionInfo.getUserId(), sessionInfo.getSessionId(), sessionInfo.getService(), ttl.getSeconds());
    }

    /**
     * 【单点 WS】注册 WebSocket 会话：在登记新 WS 前，先清理该用户的所有历史 WS 会话（返回被踢下线的旧连接）。
     * 调用方可据此主动断开旧连接（通知客户端“新设备登录”）。
     */
    public List<WebSocketSessionInfo> registerWebSocketSessionEnforceSingle(WebSocketSessionInfo sessionInfo, long ttlSeconds) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        requireText(sessionInfo.getUserId(), "userId");
        // 1) 清理并获取旧 WS 会话
        List<WebSocketSessionInfo> kicked = removeAllWebSocketSessions(sessionInfo.getUserId());
        // 2) 登记当前 WS 会话
        registerWebSocketSession(sessionInfo, ttlSeconds);
        return kicked;
    }

    /**
     * 注销单个 WebSocket 会话。
     */
    public void unregisterWebSocketSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        WebSocketSessionInfo info = getWebSocketSession(sessionId);
        if (info != null) {
            redis.opsForSet().remove(WS_USER_KEY_PREFIX + info.getUserId(), sessionId);
        }
        redis.delete(WS_SESSION_KEY_PREFIX + sessionId);
        log.debug("注销 WS 会话: sessionId={}", sessionId);
    }

    /**
     * 查询用户的所有 WebSocket 会话。
     */
    public List<WebSocketSessionInfo> getWebSocketSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> sessionIds = redis.opsForSet().members(WS_USER_KEY_PREFIX + userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<WebSocketSessionInfo> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            WebSocketSessionInfo info = getWebSocketSession(sessionId);
            if (info != null) {
                result.add(info);
            } else {
                // 清理脏数据
                redis.opsForSet().remove(WS_USER_KEY_PREFIX + userId, sessionId);
            }
        }
        return result;
    }

    /**
     * 根据 loginSessionId 查询 WebSocket 会话列表。
     * 
     * 注意：此方法需要遍历用户的所有 WebSocket 会话，性能取决于会话数量。
     * 如果 loginSessionId 为空，返回空列表。
     * 
     * @param loginSessionId 登录会话 ID（如 Keycloak 的 sid）
     * @return WebSocket 会话列表
     */
    public List<WebSocketSessionInfo> getWebSocketSessionsByLoginSessionId(String loginSessionId) {
        if (loginSessionId == null || loginSessionId.isBlank()) {
            return Collections.emptyList();
        }
        
        // 由于 WebSocket 会话没有按 loginSessionId 建立索引，需要遍历所有用户
        // 这里采用简化方案：遍历所有用户的 WebSocket 会话（性能取决于在线用户数）
        // 未来如果性能成为瓶颈，可以考虑建立 loginSessionId 索引
        
        Set<String> userIds = getAllUsersWithSessions();
        List<WebSocketSessionInfo> result = new ArrayList<>();
        
        for (String userId : userIds) {
            List<WebSocketSessionInfo> sessions = getWebSocketSessions(userId);
            for (WebSocketSessionInfo session : sessions) {
                if (loginSessionId.equals(session.getLoginSessionId())) {
                    result.add(session);
                }
            }
        }
        
        return result;
    }

    /**
     * 获取单个 WebSocket 会话详情。
     */
    public WebSocketSessionInfo getWebSocketSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().get(WS_SESSION_KEY_PREFIX + sessionId);
        if (json == null) {
            return null;
        }
        try {
            return JSON.parseObject(json, WebSocketSessionInfo.class);
        } catch (Exception ex) {
            log.warn("反序列化 WebSocketSessionInfo 失败: sessionId={}", sessionId, ex);
            return null;
        }
    }

    /**
     * 清理并返回该用户的所有 WebSocket 会话。
     */
    public List<WebSocketSessionInfo> removeAllWebSocketSessions(String userId) {
        List<WebSocketSessionInfo> sessions = getWebSocketSessions(userId);
        for (WebSocketSessionInfo session : sessions) {
            redis.opsForSet().remove(WS_USER_KEY_PREFIX + userId, session.getSessionId());
            redis.delete(WS_SESSION_KEY_PREFIX + session.getSessionId());
        }
        return sessions;
    }

    /* ============================
     * 聚合/工具方法
     * ============================ */

    /** 获取用户所有会话的聚合快照。 */
    public UserSessionSnapshot getUserSessions(String userId) {
        return UserSessionSnapshot.builder()
                .userId(userId)
                .loginSessions(getLoginSessions(userId))
                .webSocketSessions(getWebSocketSessions(userId))
                .build();
    }

    /** 清理并返回用户所有会话（用于“强制下线”）。 */
    public UserSessionSnapshot removeAllSessions(String userId) {
        return UserSessionSnapshot.builder()
                .userId(userId)
                .loginSessions(removeAllLoginSessions(userId))
                .webSocketSessions(removeAllWebSocketSessions(userId))
                .build();
    }

    /** 获取所有拥有任意会话的用户快照列表。 */
    public List<UserSessionSnapshot> getAllUserSessions() {
        Set<String> userIds = new HashSet<>();
        userIds.addAll(getUserIds(LOGIN_USER_KEY_PREFIX));
        userIds.addAll(getUserIds(WS_USER_KEY_PREFIX));
        return userIds.stream()
                .map(this::getUserSessions)
                .collect(Collectors.toList());
    }

    /** 判断用户是否在线（拥有任意一种会话即视为在线）。 */
    public boolean hasAnySession(String userId) {
        return !getLoginSessions(userId).isEmpty() || !getWebSocketSessions(userId).isEmpty();
    }

    /** 获取所有拥有会话的用户 ID 集合。 */
    public Set<String> getAllUsersWithSessions() {
        Set<String> userIds = new HashSet<>();
        userIds.addAll(getUserIds(LOGIN_USER_KEY_PREFIX));
        userIds.addAll(getUserIds(WS_USER_KEY_PREFIX));
        return userIds;
    }

    /* ============================
     * 内部工具
     * ============================ */

    /** 参数校验：字符串必须有值。 */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    /** 解析 TTL：调用方显式给出则使用，否则采用默认值。 */
    private Duration resolveTtl(long ttlSeconds, Duration defaultTtl) {
        if (ttlSeconds > 0) {
            return Duration.ofSeconds(ttlSeconds);
        }
        return defaultTtl;
    }

    /**
     * 获取拥有该前缀 Key 的所有用户 ID。
     * 说明：目前使用 KEYS，在线用户量较小时可接受；若未来在线人数增多，可替换为 SCAN。
     */
    private Set<String> getUserIds(String prefix) {
        Set<String> keys = redis.keys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        return keys.stream()
                .map(key -> key.substring(prefix.length()))
                .collect(Collectors.toSet());
    }
}
