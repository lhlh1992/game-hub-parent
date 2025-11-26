package com.gamehub.gateway.config;

import com.gamehub.gateway.handler.LoginSessionKickHandler;
import com.gamehub.gateway.service.JwtBlacklistService;
import com.gamehub.gateway.service.KeycloakSsoLogoutService;
import com.gamehub.session.SessionRegistry;
import com.gamehub.session.event.SessionInvalidatedEvent;
import com.gamehub.sessionkafkanotifier.publisher.SessionEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    // Spring Security 中注册的 Keycloak client id
    private static final String REGISTRATION_ID = "keycloak";
    // 统一的登录入口，HTML/AJAX 都会重定向到这里
    private static final URI LOGIN_REDIRECT_URI = URI.create("/oauth2/authorization/" + REGISTRATION_ID);
    // AJAX 场景通过该响应头告知前端需要跳转的地址
    private static final String REDIRECT_HEADER = "X-Auth-Redirect-To";

    // 用于在本地会话失效时同步告知 Keycloak 注销 SSO
    private final KeycloakSsoLogoutService keycloakSsoLogoutService;
    // 用于清理 Redis 中的会话数据
    private final SessionRegistry sessionRegistry;

    public SecurityConfig(KeycloakSsoLogoutService keycloakSsoLogoutService, SessionRegistry sessionRegistry) {
        this.keycloakSsoLogoutService = keycloakSsoLogoutService;
        this.sessionRegistry = sessionRegistry;
    }
 

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
                                                            ServerOAuth2AuthorizedClientRepository authorizedClientRepository,
                                                            LoginSessionKickHandler loginSessionKickHandler,
                                                            @Autowired(required = false) SessionEventPublisher sessionEventPublisher) {
        // 前后端分离 + 使用 JWT，不需要 CSRF token，直接关闭即可
        http.csrf(csrf -> csrf.disable());

        http.authorizeExchange(ex -> ex
                .pathMatchers("/", "/actuator/**").permitAll()
                .pathMatchers("/oauth2/**", "/login/**").permitAll()
                .pathMatchers("/logout").permitAll()
                .pathMatchers("/token").permitAll()
                // 验证端点,返回当前登录用户详细JWT信息
                .pathMatchers("/verify/**").permitAll()
                //静态资源放行
                .pathMatchers("/game-service/*.html", "/game-service/css/**",
                              "/game-service/js/**", "/game-service/static/**").permitAll()
                // 放行 system-service 的用户注册接口和会话监控接口（不需要认证）
                .pathMatchers("/system-service/api/users/save").permitAll()
                .pathMatchers("/system-service/api/auth/token").permitAll()
                .pathMatchers("/system-service/internal/sessions/**").permitAll()

                // 放行 事件驱动
                .pathMatchers("/system-service/internal/keycloak/events/**").permitAll()
                .anyExchange().authenticated()
        );

        // 登录：使用自定义处理器实现单点登录（后连踢前）
        http.oauth2Login(oauth2 -> oauth2
                .authenticationSuccessHandler(loginSessionKickHandler)
                // 登录失败时（如用户取消授权）也重定向到登录页，避免显示默认错误页
                .authenticationFailureHandler((exchange, ex) -> redirectToLogin(exchange.getExchange(), true))
        );

        // OAuth2 Client：支持 TokenRelay 等功能,自动将 OAuth2 token 从 Gateway 透传到下游服务
        http.oauth2Client(Customizer.withDefaults());

        // 资源服务器：使用自定义 JWT 解码器（Customizer.withDefaults() 会自动使用容器中的 ReactiveJwtDecoder Bean）
        http.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));


        // 两者都复用 handleAuthenticationFailure → 清理会话、拉黑 token、通知下游
        http.exceptionHandling(handler -> handler
                // authenticationEntryPoint：未登录访问受保护资源的入口（401）
                .authenticationEntryPoint((exchange, ex) -> handleAuthenticationFailure(exchange, blacklistService, jwtDecoder, sessionEventPublisher))
                // accessDeniedHandler：已登录但无权限时的处理（403）
                .accessDeniedHandler((exchange, denied) -> handleAuthenticationFailure(exchange, blacklistService, jwtDecoder, sessionEventPublisher))
        );

        // 登出：写入黑名单 + 发布会话失效事件 + OIDC 登出
        http.logout(l -> {
            //登出处理器(token写入黑名单,发布会话失效事件到 Kafka,移除授权客户端)
            l.logoutHandler(jwtBlacklistLogoutHandler(blacklistService, authorizedClientRepository, sessionEventPublisher));
            //登出成功后处理器
            l.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository));
            if (sessionEventPublisher == null) {
                log.warn("SessionEventPublisher Bean 未找到，登出时将不会发布会话失效事件。请检查 Kafka 配置和自动配置是否生效。");
            }
        });

        return http.build();
    }


    /**
     * OIDC 登出成功后跳回大厅页面。
     */
    @Bean
    public ServerLogoutSuccessHandler oidcLogoutSuccessHandler(ReactiveClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedServerLogoutSuccessHandler handler = new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("http://localhost:5173/");
        return handler;
    }

    /**
     * 自定义登出处理器：
     * - 读取当前 OAuth2AuthorizedClient，写入黑名单；
     * - 发布会话失效事件到 Kafka（通知各服务断开 WebSocket 连接）；
     * - 移除授权客户端，避免旧 token 被重复复用。
     */
    @Bean
    public ServerLogoutHandler jwtBlacklistLogoutHandler(JwtBlacklistService blacklistService,
                                                         ServerOAuth2AuthorizedClientRepository authorizedClientRepository,
                                                         @Autowired(required = false) SessionEventPublisher sessionEventPublisher) {
        return (WebFilterExchange exchange, Authentication authentication) -> {
            if (authentication == null) {
                return Mono.empty();
            }
            return authorizedClientRepository
                    .loadAuthorizedClient(REGISTRATION_ID, authentication, exchange.getExchange())
                    .flatMap(client -> {
                        // 1. 写入黑名单
                        Mono<Void> blacklistMono = addTokenToBlacklist(client, blacklistService);
                        
                        // 2. 发布会话失效事件（从 JWT 中提取用户 ID，如果 SessionEventPublisher 可用）
                        Mono<Void> publishMono = (sessionEventPublisher != null) 
                                ? publishSessionInvalidatedEvent(authentication, sessionEventPublisher)
                                : Mono.empty();
                        
                        // 3. 清理 SessionRegistry 中的登录会话
                        Mono<Void> cleanupMono = Mono.fromRunnable(() -> {
                            try {
                                String userId = null;
                                if (authentication.getPrincipal() instanceof Jwt jwt) {
                                    userId = jwt.getSubject();
                                } else if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal principal) {
                                    userId = principal.getName();
                                } else {
                                    userId = authentication.getName();
                                }
                                if (userId != null && !userId.isBlank()) {
                                    sessionRegistry.removeAllSessions(userId);
                                    log.info("登出时清理 SessionRegistry: userId={}", userId);
                                }
                            } catch (Exception e) {
                                log.warn("登出时清理 SessionRegistry 失败", e);
                            }
                        });
                        
                        // 并行执行，不阻塞
                        return Mono.when(blacklistMono, publishMono, cleanupMono);
                    })
                    //移除存储的 OAuth2 授权信息
                    .then(authorizedClientRepository.removeAuthorizedClient(REGISTRATION_ID, authentication, exchange.getExchange()))
                    .doOnError(ex -> log.error("登出处理失败", ex))
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

    /**
     * 发布会话失效事件到 Kafka。
     * 
     * 从 Authentication 中提取用户 ID（JWT 的 subject）和 loginSessionId（JWT 的 sid），然后发布事件。
     * 注意：SessionEventPublisher 是同步的，需要在 Mono.fromRunnable 中调用。
     */
    private Mono<Void> publishSessionInvalidatedEvent(Authentication authentication,
                                                      SessionEventPublisher sessionEventPublisher) {
        return Mono.fromRunnable(() -> {
            try {
                String userId = null;
                String loginSessionId = null;

                if (authentication.getPrincipal() instanceof Jwt jwt) {
                    userId = jwt.getSubject();
                    loginSessionId = extractLoginSessionId(jwt);
                } else if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal principal) {
                    userId = principal.getName();
                } else {
                    userId = authentication.getName();
                }

                if (userId != null && !userId.isBlank()) {
                    SessionInvalidatedEvent event = (loginSessionId != null && !loginSessionId.isBlank())
                            ? SessionInvalidatedEvent.of(userId, loginSessionId,
                            SessionInvalidatedEvent.EventType.LOGOUT, "用户主动登出")
                            : SessionInvalidatedEvent.of(userId,
                            SessionInvalidatedEvent.EventType.LOGOUT, "用户主动登出");

                    if (sessionEventPublisher != null) {
                        sessionEventPublisher.publishSessionInvalidated(event);
                    }
                    // 无论事件是否发布成功，都强制 Keycloak 端的 SSO 会话失效
                    keycloakSsoLogoutService.logout(userId, loginSessionId);
                } else {
                    log.warn("无法从 Authentication 中提取用户 ID，跳过发布会话失效事件");
                }
            } catch (Exception e) {
                log.error("发布会话失效事件失败", e);
            }
        });
    }

    /**
     * 统一处理认证/鉴权失败：清除会话、拉黑 token、清理 SessionRegistry、发布事件，然后根据请求类型响应。
     * 确保 token 失效时与手动登出效果一致。
     */
    private Mono<Void> handleAuthenticationFailure(ServerWebExchange exchange,
                                                   JwtBlacklistService blacklistService,
                                                   ReactiveJwtDecoder jwtDecoder,
                                                   SessionEventPublisher sessionEventPublisher) {
        //暂时的日志打印处理
        logAuthFailure(exchange);
        return invalidateSession(exchange)
                .then(blacklistCurrentToken(exchange, blacklistService, jwtDecoder, sessionEventPublisher))
                .then(cleanupSessionRegistry(exchange, jwtDecoder))
                .onErrorResume(ex -> {
                    log.warn("处理认证失败逻辑时发生异常，但仍将重定向到登录页", ex);
                    return Mono.empty();
                })
                .then(redirectBasedOnRequest(exchange));
    }

    /**
     * 清理 SessionRegistry 中的会话数据（401/403 时调用）。
     */
    private Mono<Void> cleanupSessionRegistry(ServerWebExchange exchange, ReactiveJwtDecoder jwtDecoder) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Mono.empty();
        }
        String token = authorization.substring("Bearer ".length());
        return jwtDecoder.decode(token)
                .flatMap(jwt -> Mono.fromRunnable(() -> {
                    try {
                        String userId = jwt.getSubject();
                        if (userId != null && !userId.isBlank()) {
                            sessionRegistry.removeAllSessions(userId);
                            log.info("认证失败时清理 SessionRegistry: userId={}", userId);
                        }
                    } catch (Exception e) {
                        log.warn("认证失败时清理 SessionRegistry 失败", e);
                    }
                }))
                .onErrorResume(ex -> Mono.empty())
                .then();
    }

    /**
     * 将失效的 token 加入黑名单并发布事件。
     * 注意：如果 token 已完全失效（如过期），decode 会失败，此时跳过处理（过期 token 本身已无效）。
     */
    private Mono<Void> blacklistCurrentToken(ServerWebExchange exchange,
                                             JwtBlacklistService blacklistService,
                                             ReactiveJwtDecoder jwtDecoder,
                                             SessionEventPublisher sessionEventPublisher) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            log.debug("认证失败但未携带 Authorization 头，path={}", exchange.getRequest().getPath());
            return Mono.empty();
        }
        String token = authorization.substring("Bearer ".length());
        return jwtDecoder.decode(token)
                .flatMap(jwt -> {
                    long ttlSeconds = 0;
                    Instant expiresAt = jwt.getExpiresAt();
                    if (expiresAt != null) {
                        ttlSeconds = Math.max(Duration.between(Instant.now(), expiresAt).getSeconds(), 0);
                    }
                    Mono<Void> blacklistMono = blacklistService.addToBlacklist(token, ttlSeconds);
                    Mono<Void> publishMono = publishSessionInvalidatedEvent(jwt, sessionEventPublisher,
                            SessionInvalidatedEvent.EventType.LOGOUT, "Token失效自动登出");
                    log.info("Token 写入黑名单: userId={}, loginSessionId={}, path={}, ttl={}s",
                            jwt.getSubject(), extractLoginSessionId(jwt),
                            exchange.getRequest().getPath(), ttlSeconds);
                    return Mono.when(blacklistMono, publishMono);
                })
                .onErrorResume(ex -> {
                    if (!(ex instanceof JwtValidationException)) {
                        log.debug("认证失败时解析 JWT 发生异常，跳过黑名单处理", ex);
                    }
                    return Mono.empty();
                });
    }

    /**
     * 从 JWT 对象发布会话失效事件到 Kafka（重载方法，用于 token 失效场景）。
     */
    private Mono<Void> publishSessionInvalidatedEvent(Jwt jwt,
                                                      SessionEventPublisher sessionEventPublisher,
                                                      SessionInvalidatedEvent.EventType eventType,
                                                      String reason) {
        if (jwt == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            try {
                String userId = jwt.getSubject();
                if (userId == null || userId.isBlank()) {
                    log.debug("JWT 中缺少 subject，跳过会话失效事件发布");
                    return;
                }
                String loginSessionId = extractLoginSessionId(jwt);
                SessionInvalidatedEvent event = (loginSessionId != null && !loginSessionId.isBlank())
                        ? SessionInvalidatedEvent.of(userId, loginSessionId, eventType, reason)
                        : SessionInvalidatedEvent.of(userId, eventType, reason);

                if (sessionEventPublisher != null) {
                    sessionEventPublisher.publishSessionInvalidated(event);
                }
                // 401/403 场景同样同步注销 Keycloak SSO，防止静默登录
                keycloakSsoLogoutService.logout(userId, loginSessionId);
            } catch (Exception e) {
                log.error("认证失败时发布会话失效事件异常", e);
            }
        });
    }

    /**
     * 从 JWT 中提取 loginSessionId（优先使用 sid，向后兼容 session_state）。
     */
    private String extractLoginSessionId(Jwt jwt) {
        Object sidObj = jwt.getClaim("sid");
        if (sidObj instanceof String sid && !sid.isBlank()) {
            return sid;
        }
        Object sessionStateObj = jwt.getClaim("session_state");
        if (sessionStateObj instanceof String sessionState && !sessionState.isBlank()) {
            return sessionState;
        }
        return null;
    }

    /**
     * 根据请求类型决定响应方式：HTML 请求返回 303 重定向，AJAX 请求返回 401 JSON。
     */
    private Mono<Void> redirectBasedOnRequest(ServerWebExchange exchange) {
        if (isHtmlRequest(exchange)) {
            return redirectToLogin(exchange, false);
        }
        return respondWithUnauthorized(exchange);
    }

    /**
     * 判断是否为普通 HTML 页面请求（非 AJAX）。
     */
    private boolean isHtmlRequest(ServerWebExchange exchange) {
        boolean acceptHtml = exchange.getRequest().getHeaders().getAccept().stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_HTML));
        String requestedWith = exchange.getRequest().getHeaders().getFirst("X-Requested-With");
        return acceptHtml && !"XMLHttpRequest".equalsIgnoreCase(requestedWith);
    }

    /**
     * 重定向到登录页：HTML 请求返回 303，AJAX 请求返回 401（前端根据响应头跳转）。
     * forceRedirect：强制重定向（用于登录失败场景）。
     */
    private Mono<Void> redirectToLogin(ServerWebExchange exchange, boolean forceRedirect) {
        ServerHttpResponse response = exchange.getResponse();
        if (!response.isCommitted()) {
            response.getHeaders().set(REDIRECT_HEADER, LOGIN_REDIRECT_URI.toString());
            if (forceRedirect || isHtmlRequest(exchange)) {
                response.setStatusCode(HttpStatus.SEE_OTHER);
                response.getHeaders().setLocation(LOGIN_REDIRECT_URI);
            } else {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
            }
        }
        return response.setComplete();
    }

    /**
     * 返回 401 JSON 响应（用于 AJAX 请求）：前端根据 X-Auth-Redirect-To 响应头弹出提示并跳转。
     */
    private Mono<Void> respondWithUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return response.setComplete();
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(REDIRECT_HEADER, LOGIN_REDIRECT_URI.toString());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = """
                {"code":"AUTH_EXPIRED","message":"登录已失效，请重新登录","redirect":"/oauth2/authorization/keycloak"}
                """.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * 失效当前 WebSession，清除服务器端会话状态。
     */
    private Mono<Void> invalidateSession(ServerWebExchange exchange) {
        return exchange.getSession()
                .flatMap(session -> session.isStarted() ? session.invalidate() : Mono.empty())
                .onErrorResume(ex -> {
                    log.debug("清理 WebSession 时出现异常，忽略继续流程", ex);
                    return Mono.empty();
                });
    }

    private void logAuthFailure(ServerWebExchange exchange) {
        try {
            log.warn("检测到 401/403，准备清理并重定向: method={}, path={}, query={}, requestId={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().pathWithinApplication().value(),
                    exchange.getRequest().getURI().getQuery(),
                    exchange.getRequest().getId());
        } catch (Exception ex) {
            log.warn("记录认证失败日志时异常", ex);
        }
    }
}


