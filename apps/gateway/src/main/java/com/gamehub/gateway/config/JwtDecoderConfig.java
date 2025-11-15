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
        return token -> jwtBlacklistService.isBlacklisted(token)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.warn("【JWT 校验】JWT 命中黑名单，拒绝访问。token={}...", shorten(token));
                        return Mono.error(new JwtException("Token has been revoked"));
                    }
                    return delegate.decode(token)
                            .flatMap(jwt -> {
                                // 步骤4：检查会话状态
                                return checkSessionStatus(jwt, token)
                                        .then(Mono.just(jwt))
                                        .doOnSuccess(j -> log.debug("【JWT 校验】JWT 验证通过，sub={}, token={}...", j.getSubject(), shorten(token)));
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
            // 1. 从 JWT 中提取 loginSessionId（优先使用 sid）
            String loginSessionId = extractLoginSessionId(jwt);
            
            log.info("【JWT 校验】开始检查会话状态: sub={}, loginSessionId={}, token前10位={}", 
                    jwt.getSubject(), loginSessionId, token != null && token.length() > 10 ? token.substring(0, 10) : token);
            
            // 2. 如果没有 loginSessionId，跳过状态检查（向后兼容）
            if (loginSessionId == null || loginSessionId.isBlank()) {
                log.warn("【JWT 校验】JWT 中没有 loginSessionId，跳过状态检查（向后兼容）: sub={}, jti={}", 
                        jwt.getSubject(), jwt.getId());
                return Mono.empty();
            }
            
            // 3. 查询 SessionRegistry，检查会话状态
            LoginSessionInfo sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
            
            // 4. 如果找不到会话，跳过状态检查（可能是旧 token 或首次登录）
            if (sessionInfo == null) {
                log.warn("【JWT 校验】SessionRegistry 中找不到会话，跳过状态检查: loginSessionId={}, sub={}, jti={}", 
                        loginSessionId, jwt.getSubject(), jwt.getId());
                return Mono.empty();
            }
            
            // 5. 验证查询到的会话是否匹配（防止查询到错误的会话）
            String jwtJti = jwt.getId(); // JWT 中的 jti
            String sessionJti = sessionInfo.getSessionId(); // SessionRegistry 中的 sessionId (jti)
            
            if (!jwtJti.equals(sessionJti)) {
                log.warn("【JWT 校验】⚠️ JWT 的 jti 与查询到的会话 sessionId 不匹配，可能查询到错误的会话: " +
                        "jwtJti={}, sessionJti={}, loginSessionId={}, sub={}", 
                        jwtJti, sessionJti, loginSessionId, jwt.getSubject());
                // 继续检查状态，但记录警告
            }
            
            // 6. 检查会话状态
            SessionStatus status = sessionInfo.getStatus();
            if (status == null) {
                // 向后兼容：如果状态为 null，默认为 ACTIVE
                status = SessionStatus.ACTIVE;
            }
            
            log.info("【JWT 校验】会话状态检查: loginSessionId={}, status={}, sub={}, sessionId={}, jwtJti={}, sessionJti={}", 
                    loginSessionId, status, jwt.getSubject(), sessionInfo.getSessionId(), jwtJti, sessionJti);
            
            if (status != SessionStatus.ACTIVE) {
                log.warn("【JWT 校验】❌ 会话状态非 ACTIVE，拒绝访问: loginSessionId={}, status={}, sub={}, token前10位={}", 
                        loginSessionId, status, jwt.getSubject(), token != null && token.length() > 10 ? token.substring(0, 10) : token);
                return Mono.error(new JwtException("Session is not active: " + status));
            }
            
            log.info("【JWT 校验】✅ 会话状态检查通过: loginSessionId={}, status={}, sub={}", 
                    loginSessionId, status, jwt.getSubject());
            return Mono.empty();
            
        } catch (Exception e) {
            // 如果检查过程中出现异常，记录日志但不阻塞（向后兼容）
            log.error("【JWT 校验】会话状态检查异常，跳过检查: sub={}", jwt.getSubject(), e);
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

