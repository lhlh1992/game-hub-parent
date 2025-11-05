package com.gamehub.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
/**
 * Gateway 安全配置（基于 WebFlux）：
 * - 明确放行的端点（健康检查、OAuth2 发起/回调、静态资源）。
 * - 其余请求统一要求认证，未登录将触发 OAuth2 登录流程。
 * - 启用 OAuth2 Client（配合 TokenRelay 透传 token）。
 * - 启用 Resource Server（校验并解析下游携带的 JWT）。
 */
public class SecurityConfig {

    // WebFlux 安全配置：负责“放行哪些路径、其余走 OAuth2 登录；并作为资源服务器校验 JWT”。

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .authorizeExchange(ex -> ex
                        .pathMatchers("/", "/actuator/**").permitAll()                 // 健康检查等放行
                        .pathMatchers("/oauth2/**", "/login/**").permitAll()            // OAuth2 发起与回调放行
                        .pathMatchers("/game-service/*.html", "/game-service/css/**",
                                      "/game-service/js/**", "/game-service/static/**").permitAll() // 静态资源放行
                        .pathMatchers("/token").authenticated()                           // 仅登录用户可取 token
                        .anyExchange().authenticated()                                      // 其余均需认证
                )
                .oauth2Login(Customizer.withDefaults())                                     // 使用默认 OAuth2 登录流程
                .oauth2Client(Customizer.withDefaults())                                    // 启用 OAuth2 客户端能力（TokenRelay 依赖）
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));               // 作为资源服务器校验 JWT

        return http.build();
    }
}


