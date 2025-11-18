package com.gamehub.gateway.handler;

import com.gamehub.gateway.service.JwtBlacklistService;
import com.gamehub.gateway.service.KeycloakSsoLogoutService;
import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.LoginSessionInfo;
import com.gamehub.session.model.SessionStatus;
import com.gamehub.session.event.SessionInvalidatedEvent;
import com.gamehub.sessionkafkanotifier.publisher.SessionEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录成功处理器：实现单点登录（后连踢前）功能。
 * 
 * 职责：
 * 1. 从 JWT 中提取 loginSessionId（sid）、sessionId（jti）、userId（sub）
 * 2. 构建 LoginSessionInfo 并调用 registerLoginSessionEnforceSingle
 * 3. 将被踢掉的旧会话 token 加入黑名单
 * 4. 可选：发布 SESSION_KICKED 事件
 * 
 * 注意：此处理器在登录成功后执行，此时 OAuth2AuthorizedClient 已经保存。
 */
@Slf4j
@Component
public class LoginSessionKickHandler implements ServerAuthenticationSuccessHandler {

    private static final String REGISTRATION_ID = "keycloak";
    
    private static final String SESSION_LOGIN_SESSION_ID_KEY = "LOGIN_SESSION_ID";
    
    private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;
    private final SessionRegistry sessionRegistry;
    private final JwtBlacklistService blacklistService;
    private final ServerAuthenticationSuccessHandler defaultSuccessHandler;
    private final SessionEventPublisher sessionEventPublisher;
    private final KeycloakSsoLogoutService keycloakSsoLogoutService;

    public LoginSessionKickHandler(
            ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
            SessionRegistry sessionRegistry,
            JwtBlacklistService blacklistService,
            @Autowired(required = false) SessionEventPublisher sessionEventPublisher,
            KeycloakSsoLogoutService keycloakSsoLogoutService) {
        this.authorizedClientManager = authorizedClientManager;
        this.sessionRegistry = sessionRegistry;
        this.blacklistService = blacklistService;
        this.sessionEventPublisher = sessionEventPublisher;
        this.keycloakSsoLogoutService = keycloakSsoLogoutService;
        // 使用默认的成功处理器（用于重定向）
        this.defaultSuccessHandler = new org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler("/");
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange exchange, Authentication authentication) {
        // 步骤1：获取 OAuth2AuthorizedClient（包含 access_token）
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(REGISTRATION_ID)
                .principal(authentication)
                .build();

        return authorizedClientManager.authorize(authorizeRequest)
                .flatMap(authorizedClient -> {
                    if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                        log.warn("登录成功但未找到 OAuth2AuthorizedClient，跳过会话管理");
                        return defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication);
                    }

                    OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
                    String tokenValue = accessToken.getTokenValue();
                    
                    // 步骤2：解析 JWT，提取 userId、sessionId(jti)、loginSessionId(sid)
                    return extractJwtInfo(tokenValue)
                            .flatMap(jwtInfo -> {
                                String userId = (String) jwtInfo.get("userId");
                                String sessionId = (String) jwtInfo.get("sessionId"); // jti
                                String loginSessionId = (String) jwtInfo.get("loginSessionId"); // sid
                                Instant expiresAt = (Instant) jwtInfo.get("expiresAt");
                                Instant issuedAt = (Instant) jwtInfo.get("issuedAt");

                                if (userId == null || sessionId == null) {
                                    log.warn("无法从 JWT 中提取 userId 或 sessionId，跳过会话管理");
                                    return defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication);
                                }

                                // 步骤3：构建 LoginSessionInfo
                                LoginSessionInfo newSession = LoginSessionInfo.builder()
                                        .sessionId(sessionId)
                                        .loginSessionId(loginSessionId)
                                        .userId(userId)
                                        .token(tokenValue)
                                        .status(SessionStatus.ACTIVE)
                                        .issuedAt(issuedAt != null ? issuedAt.toEpochMilli() : Instant.now().toEpochMilli())
                                        .expiresAt(expiresAt != null ? expiresAt.toEpochMilli() : null)
                                        .attributes(buildAttributes(exchange.getExchange()))
                                        .build();

                                // 步骤4：计算 TTL
                                long ttlSeconds = 0;
                                if (expiresAt != null) {
                                    ttlSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
                                    ttlSeconds = Math.max(ttlSeconds, 0);
                                }

                                // 步骤5：注册新会话，标记旧会话为 KICKED（单点登录核心）
                                List<LoginSessionInfo> kickedSessions = sessionRegistry.registerLoginSessionEnforceSingle(newSession, ttlSeconds);
                                
                                log.info("【单点登录】新登录会话已注册: userId={}, sessionId={}, loginSessionId={}, 踢掉旧会话数={}", 
                                        userId, sessionId, loginSessionId, kickedSessions.size());

                                // 步骤6：存储 loginSessionId 到 HTTP Session，黑名单旧 token，发布踢下线事件
                                return storeLoginSessionIdInSession(exchange.getExchange(), loginSessionId)
                                        .then(blacklistKickedSessions(kickedSessions))
                                        .then(publishKickedEvent(userId, loginSessionId, kickedSessions))
                                         // 调用默认成功处理器，重定向到首页
                                        .then(defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication));
                            })
                            .onErrorResume(ex -> {
                                log.error("登录会话管理失败", ex);
                                // 即使失败，也继续执行默认的成功处理器，不阻塞登录流程
                                return defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication);
                            });
                })
                .onErrorResume(ex -> {
                    log.error("获取 OAuth2AuthorizedClient 失败", ex);
                    // 即使失败，也继续执行默认的成功处理器
                    return defaultSuccessHandler.onAuthenticationSuccess(exchange, authentication);
                });
    }

    /**
     * 从 JWT token 中提取信息。
     * 
     * 注意：这里需要手动解析 JWT，因为此时 JWT 还没有被 Spring Security 解析。
     * 我们可以使用 ReactiveJwtDecoder 来解析，但需要注入它。
     * 或者，我们可以从 Authentication 中获取 JWT（如果已经是 JwtAuthenticationToken）。
     */
    private Mono<Map<String, Object>> extractJwtInfo(String tokenValue) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            
            // 尝试从 Authentication 中获取 JWT（如果已经是 JwtAuthenticationToken）
            // 但登录成功时，Authentication 可能还不是 JwtAuthenticationToken
            // 所以我们需要手动解析 JWT
            
            // 简化方案：使用 JWT 库手动解析（这里使用 Base64 解码）
            // 注意：这只是为了提取 claim，不验证签名（签名验证由 Spring Security 负责）
            try {
                String[] parts = tokenValue.split("\\.");
                if (parts.length < 2) {
                    throw new IllegalArgumentException("Invalid JWT format");
                }
                
                // 解码 payload（Base64URL）
                String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(payload);
                
                // 提取所需信息
                result.put("userId", json.getString("sub"));
                result.put("sessionId", json.getString("jti"));
                result.put("loginSessionId", extractSid(json)); // 从 sid claim 提取
                
                // 提取时间信息
                Long exp = json.getLong("exp");
                Long iat = json.getLong("iat");
                result.put("expiresAt", exp != null ? Instant.ofEpochSecond(exp) : null);
                result.put("issuedAt", iat != null ? Instant.ofEpochSecond(iat) : null);
                
                return result;
            } catch (Exception e) {
                log.error("解析 JWT 失败", e);
                throw new RuntimeException("Failed to parse JWT", e);
            }
        });
    }

    /**
     * 从 JWT claim 中提取 sid（loginSessionId）。
     * 
     * 根据步骤0的验证结果，sid 在 JWT claim 中是可用的。
     */
    private String extractSid(com.alibaba.fastjson2.JSONObject json) {
        // 优先使用 sid
        String sid = json.getString("sid");
        if (sid != null && !sid.isBlank()) {
            return sid;
        }
        
        // 如果没有 sid，尝试使用 session_state（向后兼容）
        String sessionState = json.getString("session_state");
        if (sessionState != null && !sessionState.isBlank()) {
            return sessionState;
        }
        
        return null;
    }

    /**
     * 将被踢掉的旧会话 token 加入黑名单。
     */
    private Mono<Void> blacklistKickedSessions(List<LoginSessionInfo> kickedSessions) {
        if (kickedSessions.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            for (LoginSessionInfo kickedSession : kickedSessions) {
                if (kickedSession.getToken() != null && !kickedSession.getToken().isBlank()) {
                    // 计算剩余 TTL
                    long ttlSeconds = 0;
                    if (kickedSession.getExpiresAt() != null && kickedSession.getExpiresAt() > 0) {
                        ttlSeconds = (kickedSession.getExpiresAt() - Instant.now().toEpochMilli()) / 1000;
                        ttlSeconds = Math.max(ttlSeconds, 0);
                    }
                    
                    // 加入黑名单
                    blacklistService.addToBlacklist(kickedSession.getToken(), ttlSeconds)
                            .subscribe(
                                    null,
                                    error -> log.warn("将旧会话 token 加入黑名单失败: sessionId={}", kickedSession.getSessionId(), error)
                            );
                    
                    log.debug("已将旧会话 token 加入黑名单: sessionId={}, loginSessionId={}", 
                            kickedSession.getSessionId(), kickedSession.getLoginSessionId());
                }
            }
        });
    }

    /**
     * 发布 SESSION_KICKED 事件（可选）。
     */
    /**
     * 向下游发布被踢事件，并同步注销 Keycloak SSO 中的对应会话。
     */
    private Mono<Void> publishKickedEvent(String userId, String loginSessionId, List<LoginSessionInfo> kickedSessions) {
        if (kickedSessions.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            for (LoginSessionInfo kickedSession : kickedSessions) {
                String kickedLoginSessionId = kickedSession.getLoginSessionId();
                if (kickedLoginSessionId == null || kickedLoginSessionId.isBlank()) {
                    continue;
                }
                try {
                    if (sessionEventPublisher != null) {
                        SessionInvalidatedEvent event = SessionInvalidatedEvent.of(
                                userId,
                                kickedLoginSessionId,
                                SessionInvalidatedEvent.EventType.FORCE_LOGOUT,
                                "单点登录：被新登录踢下线"
                        );
                        sessionEventPublisher.publishSessionInvalidated(event);
                        log.debug("已发布 SESSION_KICKED 事件: userId={}, loginSessionId={}",
                                userId, kickedLoginSessionId);
                    }
                } catch (Exception e) {
                    log.error("发布 SESSION_KICKED 事件失败: userId={}, loginSessionId={}",
                            userId, kickedLoginSessionId, e);
                } finally {
                    try {
                        keycloakSsoLogoutService.logout(userId, kickedLoginSessionId);
                    } catch (Exception ex) {
                        log.warn("Keycloak SSO 注销失败: userId={}, loginSessionId={}",
                                userId, kickedLoginSessionId, ex);
                    }
                }
            }
        });
    }

    /**
     * 将 loginSessionId 存储到 HTTP Session 中。
     * 
     * 用于后续在 TokenController 中验证返回的 token 是否属于当前 Session。
     */
    private Mono<Void> storeLoginSessionIdInSession(ServerWebExchange exchange, String loginSessionId) {
        if (loginSessionId == null || loginSessionId.isBlank()) {
            return Mono.empty();
        }
        
        return exchange.getSession()
                .doOnNext((WebSession session) -> {
                    session.getAttributes().put(SESSION_LOGIN_SESSION_ID_KEY, loginSessionId);
                    log.debug("已将 loginSessionId 存储到 HTTP Session: loginSessionId={}, sessionId={}", 
                            loginSessionId, session.getId());
                })
                .then();
    }

    /**
     * 构建会话属性（如 IP、User-Agent 等）。
     */
    private Map<String, String> buildAttributes(ServerWebExchange exchange) {
        Map<String, String> attributes = new HashMap<>();
        
        // 提取 IP
        String ip = exchange.getRequest().getRemoteAddress() != null 
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() 
                : null;
        if (ip != null) {
            attributes.put("ip", ip);
        }
        
        // 提取 User-Agent
        String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
        if (userAgent != null) {
            attributes.put("userAgent", userAgent);
        }
        
        return attributes;
    }
}

