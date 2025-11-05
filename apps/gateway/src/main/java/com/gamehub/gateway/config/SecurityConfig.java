package com.gamehub.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
 

/**
 * Gateway 安全配置（基于 WebFlux）：
 * - 明确放行的端点（健康检查、OAuth2 发起/回调、静态资源）。
 * - 其余请求统一要求认证，未登录将触发 OAuth2 登录流程。
 * - 启用 OAuth2 Client（配合 TokenRelay 透传 token）。
 * - 启用 Resource Server（校验并解析下游携带的 JWT）。
 */
@Configuration
public class SecurityConfig {



    /**
     * WebFlux 安全配置：负责放行规则、登录方式、资源服务器与登出行为。
     * @param http Spring 提供的构造器对象，用它来搭建安全策略
     * @param clientRegistrationRepository 从配置文件加载的 OAuth2 客户端注册信息，后面配置 OIDC 登出要用它（知道去哪个 IdP、哪个 client）
     * @return
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        // 关闭 CSRF：前后端分离，保证前端可直接提交 POST /logout
        http.csrf(csrf -> csrf.disable());

        // 访问授权规则：只放行根、健康检查、OAuth2 相关、登出入口与静态资源;其他所有请求都需要认证（未登录会被送去 OAuth2 登录流程）。
        http.authorizeExchange(ex -> ex
                .pathMatchers("/", "/actuator/**").permitAll()
                .pathMatchers("/oauth2/**", "/login/**").permitAll()
                .pathMatchers("/logout").permitAll()
                .pathMatchers("/game-service/*.html", "/game-service/css/**", 
                              "/game-service/js/**", "/game-service/static/**").permitAll()
                .pathMatchers("/token").authenticated()
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

    // OIDC 登出成功处理器：重定向到 Keycloak end_session，随后回到 {baseUrl}/
    @Bean
    public ServerLogoutSuccessHandler oidcLogoutSuccessHandler(ReactiveClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedServerLogoutSuccessHandler handler = new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}


