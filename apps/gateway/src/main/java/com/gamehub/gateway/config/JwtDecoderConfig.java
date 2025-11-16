package com.gamehub.gateway.config;

import com.gamehub.gateway.service.JwtBlacklistService;
import com.gamehub.session.SessionRegistry;
import com.gamehub.session.model.LoginSessionInfo;
import com.gamehub.session.model.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 网关使用的 JWT 解码器：
 * - 先检查黑名单，再委托默认的 Nimbus 解码器完成验签；
 * - 命中黑名单则直接抛出异常，让 Spring Security 返回 401。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtDecoderConfig {

    private final JwtBlacklistService jwtBlacklistService;
    private final SessionRegistry sessionRegistry;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        ReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
        return token -> 
                // 步骤1：检查黑名单
                jwtBlacklistService.isBlacklisted(token)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.warn("JWT 命中黑名单，拒绝访问");
                        return Mono.error(new JwtException("Token has been revoked"));
                    }
                    // 步骤2：验证签名并解析 JWT
                    return delegate.decode(token)
                            .flatMap(jwt -> {
                                // 步骤3：检查会话状态
                                return checkSessionStatus(jwt, token)
                                        .then(Mono.just(jwt));
                            });
                });
    }

    /**
     * 检查会话状态。
     * 
     * 步骤4：在 JWT 校验时，添加会话状态检查。
     * - 从 JWT 中提取 loginSessionId（sid）
     * - 查询 SessionRegistry，检查会话状态
     * - 如果状态非 ACTIVE，拒绝访问
     * 
     * 向后兼容：
     * - 如果 JWT 中没有 loginSessionId，跳过状态检查（向后兼容旧 token）
     * - 如果 SessionRegistry 中找不到会话，也跳过状态检查（可能是旧 token 或首次登录）
     */
    private Mono<Void> checkSessionStatus(Jwt jwt, String token) {
        try {
            // 提取 loginSessionId（优先使用 sid）
            String loginSessionId = extractLoginSessionId(jwt);
            
            if (loginSessionId == null || loginSessionId.isBlank()) {
                log.debug("JWT 中没有 loginSessionId，跳过状态检查（向后兼容）: sub={}", jwt.getSubject());
                return Mono.empty();
            }
            
            // 查询 SessionRegistry
            LoginSessionInfo sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
            
            if (sessionInfo == null) {
                log.debug("SessionRegistry 中找不到会话，跳过状态检查: loginSessionId={}, sub={}", 
                        loginSessionId, jwt.getSubject());
                return Mono.empty();
            }
            
            // 验证 jti 匹配（防止查询到错误的会话）
            String jwtJti = jwt.getId();
            String sessionJti = sessionInfo.getSessionId();
            
            if (!jwtJti.equals(sessionJti)) {
                log.warn("JWT 的 jti 与会话的 sessionId 不匹配: jwtJti={}, sessionJti={}, loginSessionId={}, sub={}", 
                        jwtJti, sessionJti, loginSessionId, jwt.getSubject());
            }
            
            // 检查会话状态
            SessionStatus status = sessionInfo.getStatus();
            if (status == null) {
                status = SessionStatus.ACTIVE;
            }
            
            if (status != SessionStatus.ACTIVE) {
                log.warn("会话状态非 ACTIVE，拒绝访问: loginSessionId={}, status={}, sub={}", 
                        loginSessionId, status, jwt.getSubject());
                return Mono.error(new JwtException("Session is not active: " + status));
            }
            
            return Mono.empty();
            
        } catch (Exception e) {
            log.error("会话状态检查异常，跳过检查: sub={}", jwt.getSubject(), e);
            return Mono.empty();
        }
    }

    /**
     * 从 JWT 中提取 loginSessionId。
     * 
     * 优先使用 sid，如果没有则尝试使用 session_state（向后兼容）。
     */
    private String extractLoginSessionId(Jwt jwt) {
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
        
        return null;
    }

    private String shorten(String token) {
        if (token == null) {
            return "null";
        }
        int length = token.length();
        return length <= 10 ? token : token.substring(0, 5) + "..." + token.substring(length - 5);
    }
}

