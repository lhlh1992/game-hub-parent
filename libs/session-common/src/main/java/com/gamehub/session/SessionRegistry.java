package com.gamehub.session;

import com.alibaba.fastjson2.JSON;
import com.gamehub.session.config.SessionRedisConfig;
import com.gamehub.session.model.LoginSessionInfo;
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
    /** Redis Key 前缀：登录会话详情（按 tokenId 存储） */
    private static final String LOGIN_SESSION_KEY_PREFIX = "session:login:token:";
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

        Duration ttl = resolveTtl(ttlSeconds, DEFAULT_LOGIN_TTL);
        String userKey = LOGIN_USER_KEY_PREFIX + sessionInfo.getUserId();
        String sessionKey = LOGIN_SESSION_KEY_PREFIX + sessionInfo.getSessionId();

        redis.opsForSet().add(userKey, sessionInfo.getSessionId());
        redis.opsForValue().set(sessionKey, JSON.toJSONString(sessionInfo), ttl);
        log.debug("注册登录会话: userId={}, sessionId={}, ttl={}s", sessionInfo.getUserId(), sessionInfo.getSessionId(), ttl.getSeconds());
    }

    /**
     * 【单点登录】注册登录会话：在登记新会话前，先清理该用户的所有历史登录会话（返回被踢下线的旧会话）。
     * 调用方可据此对旧 token 执行黑名单处理。
     */
    public List<LoginSessionInfo> registerLoginSessionEnforceSingle(LoginSessionInfo sessionInfo, long ttlSeconds) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        requireText(sessionInfo.getUserId(), "userId");
        // 1) 清理并获取旧登录会话
        List<LoginSessionInfo> kicked = removeAllLoginSessions(sessionInfo.getUserId());
        // 2) 登记当前会话
        registerLoginSession(sessionInfo, ttlSeconds);
        return kicked;
    }

    /**
     * 注销单个登录会话。
     */
    public void unregisterLoginSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        LoginSessionInfo info = getLoginSession(sessionId);
        if (info != null) {
            redis.opsForSet().remove(LOGIN_USER_KEY_PREFIX + info.getUserId(), sessionId);
        }
        redis.delete(LOGIN_SESSION_KEY_PREFIX + sessionId);
        log.debug("注销登录会话: sessionId={}", sessionId);
    }

    /**
     * 查询用户的所有登录会话。
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
                result.add(info);
            } else {
                // 清理脏数据
                redis.opsForSet().remove(LOGIN_USER_KEY_PREFIX + userId, sessionId);
            }
        }
        return result;
    }

    /**
     * 获取单个登录会话详情。
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
            return JSON.parseObject(json, LoginSessionInfo.class);
        } catch (Exception ex) {
            log.warn("反序列化 LoginSessionInfo 失败: sessionId={}", sessionId, ex);
            return null;
        }
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
