package com.gamehub.gameservice.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 最小化的安全配置（Resource Server）
 * -------------------------------------------------------
 * 目标：作为下游资源服务器验证从网关透传的 JWT，
 *      不引入 OAuth2 登录，也不自定义全局类型转换器，
 *      以降低启动期与 MVC 的耦合与故障点。
 *
 * 关键点：
 *  - 只开启 JWT 资源服务器能力（oauth2ResourceServer().jwt()）。
 *  - 放行 /actuator/** 与 /public/**；其余路径要求已认证。
 *  - 角色映射、细粒度授权可在链路跑通后按需追加。
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 说明：
        // 1) 关闭 CSRF（纯后端 API / 网关前置的场景下可简化处理）。
        // 2) 配置 URL 授权规则：/actuator/**、/public/** 直接放行，其余必须认证。
        // 3) 启用 JWT 资源服务器，Spring 会依据 application.yml 的 issuer-uri/jwk-set-uri 自动解码与验签。
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/public/**").permitAll()
                        // 放行静态资源（HTML/CSS/JS），这些文件本身不需要认证
                        // 但访问 REST API 和 WebSocket 时仍需要认证
                        .requestMatchers("/*.html", "/css/**", "/js/**", "/static/**").permitAll()
                        .anyRequest().authenticated()
                )
                // 启用 JWT 验证：自动验证所有需要认证的请求的 JWT Token（从 Authorization: Bearer <token> header 提取）
                .oauth2ResourceServer(oauth -> oauth.jwt());
        return http.build();
    }
}
