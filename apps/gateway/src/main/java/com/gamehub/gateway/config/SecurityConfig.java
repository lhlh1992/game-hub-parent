package com.gamehub.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .authorizeExchange(ex -> ex
                        .pathMatchers("/", "/actuator/**").permitAll()
                        // 放行 OAuth2 登录相关端点
                        .pathMatchers("/oauth2/**", "/login/**").permitAll()
                        // 放行静态资源（HTML/CSS/JS），这些文件本身不需要认证
                        // 但访问 REST API 和 WebSocket 时仍需要认证
                        .pathMatchers("/game-service/*.html", "/game-service/css/**", "/game-service/js/**", "/game-service/static/**").permitAll()
                        // /token 端点需要认证（只有登录用户才能获取 token）
                        .pathMatchers("/token").authenticated()
                        .anyExchange().authenticated()
                )
                .oauth2Login(Customizer.withDefaults())     // WebFlux 的登录（默认会重定向到登录前的页面）
                .oauth2Client(Customizer.withDefaults())    // 启用 OAuth2 Client
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())); // ✅ 新写法

        return http.build();
    }
}


