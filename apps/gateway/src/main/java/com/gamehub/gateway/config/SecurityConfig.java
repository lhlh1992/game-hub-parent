package com.gamehub.gateway.config;

import com.gamehub.gateway.service.JwtBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.WebFilterExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
 
 
/**
 * Spring Cloud Gateway 的核心安全配置：
 * 1. 负责 OAuth2 登录、授权和资源服务器配置；
 * 2. 在登出时写入黑名单 + 移除 OAuth2AuthorizedClient，确保重新登录拿到新 token；
 * 3. 对下游资源请求统一启用 JWT 验签（ReactiveJwtDecoder 内部含黑名单检查）。
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String REGISTRATION_ID = "keycloak";
 

    /**
     * 核心过滤链：
     * - 放行基础路径、登录、登出、token 获取等端点；
     * - 其余请求需要认证；
     * - 启用 OAuth2 登录 / 客户端；
     * - 自定义资源服务器的 JWT 解码器（含黑名单检查）；
     * - 登出流程提前写黑名单、清除授权客户端，再走 OIDC 退出。
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                            ReactiveClientRegistrationRepository clientRegistrationRepository,
                                                            ReactiveJwtDecoder jwtDecoder,
                                                            JwtBlacklistService blacklistService,
                                                            ServerOAuth2AuthorizedClientRepository authorizedClientRepository) {
        // 前后端分离 + 使用 JWT，不需要 CSRF token，直接关闭即可
        http.csrf(csrf -> csrf.disable());

        http.authorizeExchange(ex -> ex
                .pathMatchers("/", "/actuator/**").permitAll()
                .pathMatchers("/oauth2/**", "/login/**").permitAll()
                .pathMatchers("/logout").permitAll()
                .pathMatchers("/token").permitAll()
                .pathMatchers("/game-service/*.html", "/game-service/css/**",
                              "/game-service/js/**", "/game-service/static/**").permitAll()
                // 放行 system-service 的用户注册接口（不需要认证）
                .pathMatchers("/system-service/api/users/save").permitAll()
                .pathMatchers("/system-service/api/auth/token").permitAll()

                // 放行 事件驱动
                .pathMatchers("/system-service/internal/keycloak/events/**").permitAll()
                .anyExchange().authenticated()
        );

        // 登录：使用默认 OAuth2 客户端登录（存在 SavedRequest 则回原地址，否则回“/”）
        http.oauth2Login(Customizer.withDefaults());

        // OAuth2 Client 能力（TokenRelay 等）
        http.oauth2Client(Customizer.withDefaults());

        // 资源服务器：启用 JWT 校验
        http.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));

        // 登出：使用 OIDC 客户端发起登出，成功后回到网关根路径（由路由再跳大厅）
        http.logout(l -> l.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)));

        return http.build();
    }

    /**
     * OIDC 登出成功后跳回大厅页面。
     */
    @Bean
    public ServerLogoutSuccessHandler oidcLogoutSuccessHandler(ReactiveClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedServerLogoutSuccessHandler handler = new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}/game-service/lobby.html");
        return handler;
    }

    /**
     * 自定义登出处理器：
     * - 读取当前 OAuth2AuthorizedClient，写入黑名单；
     * - 移除授权客户端，避免旧 token 被重复复用。
     */
    @Bean
    public ServerLogoutHandler jwtBlacklistLogoutHandler(JwtBlacklistService blacklistService,
                                                         ServerOAuth2AuthorizedClientRepository authorizedClientRepository) {
        return (WebFilterExchange exchange, Authentication authentication) -> {
            if (authentication == null) {
                return Mono.empty();
            }
            return authorizedClientRepository
                    .loadAuthorizedClient(REGISTRATION_ID, authentication, exchange.getExchange())
                    .flatMap(client -> addTokenToBlacklist(client, blacklistService))
                    .then(authorizedClientRepository.removeAuthorizedClient(REGISTRATION_ID, authentication, exchange.getExchange()))
                    .doOnError(ex -> log.error("写入黑名单或移除授权客户端失败", ex))
                    .onErrorResume(ex -> Mono.empty());
        };
    }

    /**
     * 写黑名单前计算剩余 TTL，保证 Redis key 的生命周期与 token 一致。
     */
    private Mono<Void> addTokenToBlacklist(OAuth2AuthorizedClient client, JwtBlacklistService blacklistService) {
        if (client == null || client.getAccessToken() == null) {
            return Mono.empty();
        }
        String token = client.getAccessToken().getTokenValue();
        Instant expiresAt = client.getAccessToken().getExpiresAt();
        long expiresIn = 0;
        if (expiresAt != null) {
            expiresIn = Duration.between(Instant.now(), expiresAt).getSeconds();
        }
        expiresIn = Math.max(expiresIn, 0);
        return blacklistService.addToBlacklist(token, expiresIn);
    }
}


