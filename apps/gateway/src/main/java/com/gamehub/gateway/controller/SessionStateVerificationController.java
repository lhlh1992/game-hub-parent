package com.gamehub.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态验证控制器 - 用于验证 Keycloak session_state 是否可用
 * 
 * 此控制器用于步骤0的验证，检查：
 * 1. JWT claim 中是否有 session_state 或 sid
 * 2. OAuth2 授权响应中是否有 session_state
 * 3. Token 刷新后 session_state 是否稳定
 * 
 * 验证完成后，此控制器可以删除或保留作为调试工具。
 */
@Slf4j
@RestController
public class SessionStateVerificationController {

    private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;
    private final ReactiveJwtDecoder jwtDecoder;

    public SessionStateVerificationController(
            ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
            ReactiveJwtDecoder jwtDecoder) {
        this.authorizedClientManager = authorizedClientManager;
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * 验证端点：检查当前 token 的 JWT claim 信息
     * 
     * 访问方式：GET /verify/session-state
     * 需要先登录，然后访问此端点
     */
    @GetMapping("/verify/session-state")
    public Mono<ResponseEntity<Map<String, Object>>> verifySessionState(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.just(ResponseEntity.status(401).body(
                    Map.<String, Object>of("error", "未登录", "message", "请先通过 /oauth2/authorization/keycloak 登录")
            ));
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId("keycloak")
                .principal(authentication)
                .build();

        return authorizedClientManager.authorize(authorizeRequest)
                .flatMap(authorizedClient -> {
                    if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                        return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
                                Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
                        ));
                    }

                    OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
                    String tokenValue = accessToken.getTokenValue();

                    // 解析 JWT，提取所有相关信息
                    return jwtDecoder.decode(tokenValue)
                            .map(jwt -> {
                                Map<String, Object> result = new HashMap<>();
                                
                                // 基本信息
                                result.put("userId", jwt.getSubject());
                                result.put("jti", jwt.getId());
                                result.put("issuedAt", jwt.getIssuedAt() != null ? jwt.getIssuedAt().toString() : null);
                                result.put("expiresAt", jwt.getExpiresAt() != null ? jwt.getExpiresAt().toString() : null);
                                
                                // 检查 session_state（可能在 claim 中）
                                Object sessionStateObj = jwt.getClaim("session_state");
                                String sessionState = null;
                                if (sessionStateObj != null) {
                                    String str = sessionStateObj.toString();
                                    sessionState = (str != null && !str.trim().isEmpty()) ? str : null;
                                }
                                result.put("session_state_in_jwt", sessionState != null ? sessionState : "NOT_FOUND");
                                
                                // 检查 sid（自定义 claim，如果配置了 Mapper）
                                Object sidObj = jwt.getClaim("sid");
                                String sid = null;
                                if (sidObj != null) {
                                    String str = sidObj.toString();
                                    sid = (str != null && !str.trim().isEmpty()) ? str : null;
                                }
                                result.put("sid_in_jwt", sid != null ? sid : "NOT_FOUND");
                                
                                // 注意：OAuth2 响应中的 session_state 在 Spring Security 中通常不可直接获取
                                // 主要依赖 JWT claim 中的 session_state 或 sid
                                result.put("session_state_in_oauth2_response", "NOT_AVAILABLE (Spring Security 不直接暴露)");
                                
                                // 列出所有 JWT claim（用于调试）
                                Set<String> allClaims = jwt.getClaims().keySet();
                                result.put("all_jwt_claims", allClaims);
                                
                                // 提取所有 claim 的值（用于详细分析）
                                Map<String, Object> claimValues = new HashMap<>();
                                for (String claim : allClaims) {
                                    Object value = jwt.getClaim(claim);
                                    // 只记录字符串和简单类型，避免序列化问题
                                    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                                        claimValues.put(claim, value);
                                    } else {
                                        claimValues.put(claim, value != null ? value.getClass().getSimpleName() : "null");
                                    }
                                }
                                result.put("jwt_claim_values", claimValues);
                                
                                // 验证结果总结
                                Map<String, Object> verification = new HashMap<>();
                                verification.put("session_state_available_in_jwt", sessionState != null);
                                verification.put("sid_available_in_jwt", sid != null);
                                verification.put("recommended_loginSessionId_source", 
                                        determineRecommendedSource(sessionState, sid));
                                verification.put("verification_status", 
                                        (sessionState != null || sid != null) ? "PASS" : "FAIL");
                                result.put("verification", verification);
                                
                                log.info("【验证】用户 {} 的 session_state 验证结果: session_state={}, sid={}, 推荐来源={}", 
                                        jwt.getSubject(), 
                                        sessionState != null ? sessionState : "NOT_FOUND",
                                        sid != null ? sid : "NOT_FOUND",
                                        verification.get("recommended_loginSessionId_source"));
                                
                                return ResponseEntity.ok(result);
                            })
                            .onErrorResume(ex -> {
                                log.error("验证 session_state 失败", ex);
                                return Mono.just(ResponseEntity.status(500).<Map<String, Object>>body(
                                        Map.<String, Object>of("error", "验证失败", "message", ex.getMessage())
                                ));
                            });
                })
                .defaultIfEmpty(ResponseEntity.status(401).body(
                        Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
                ));
    }

    /**
     * 确定推荐的 loginSessionId 来源
     */
    private String determineRecommendedSource(String sessionState, String sid) {
        if (sessionState != null && !sessionState.trim().isEmpty()) {
            return "session_state (在 JWT claim 中)";
        }
        if (sid != null && !sid.trim().isEmpty()) {
            return "sid (在 JWT claim 中，需要配置 Keycloak Mapper)";
        }
        return "需要配置 Keycloak Mapper 或使用 Gateway 生成 UUID";
    }
}

