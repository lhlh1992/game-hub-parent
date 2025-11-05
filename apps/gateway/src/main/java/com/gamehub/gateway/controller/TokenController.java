package com.gamehub.gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Token 控制器 - 提供给前端获取当前登录用户的 access_token
 * 
 * 注意：Gateway 使用 WebFlux，OAuth2 登录后的 token 保存在服务器端 session 中。
 * 前端通过此端点获取 token，用于后续的 API 调用和 WebSocket 连接。
 */
@RestController
public class TokenController {

    private final ReactiveOAuth2AuthorizedClientService authorizedClientService;

    public TokenController(ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * 获取当前登录用户的 access_token
     * 需要用户已通过 Gateway 的 OAuth2 登录流程认证
     */
    @GetMapping("/token")
    public Mono<ResponseEntity<Map<String, Object>>> getToken(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.just(ResponseEntity.status(401).body(
                    Map.<String, Object>of("error", "未登录", "message", "请先通过 /oauth2/authorization/keycloak 登录")
            ));
        }
        
        String clientRegistrationId = "keycloak";
        String principalName = authentication.getName();
        
        return authorizedClientService.loadAuthorizedClient(clientRegistrationId, principalName)
                .map(authorizedClient -> {
                    if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                        return ResponseEntity.status(401).<Map<String, Object>>body(
                                Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
                        );
                    }
                    
                    OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
                    String tokenValue = accessToken.getTokenValue();
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("access_token", tokenValue);
                    result.put("token_type", accessToken.getTokenType().getValue());
                    if (accessToken.getExpiresAt() != null) {
                        result.put("expires_at", accessToken.getExpiresAt().toEpochMilli());
                    }
                    
                    return ResponseEntity.ok(result);
                })
                .defaultIfEmpty(ResponseEntity.status(401).body(
                        Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
                ));
    }
}

