package com.gamehub.systemservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 * 作为 OAuth2 Resource Server，验证从 Gateway 传来的 JWT Token
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 关闭 CSRF（使用 JWT，不需要 CSRF 保护）
                .csrf(csrf -> csrf.disable())
                
                // 无状态会话（使用 JWT，不需要 Session）
                .sessionManagement(session -> 
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                
                // 授权规则
                .authorizeHttpRequests(auth -> auth
                        // 健康检查端点放行
                        .requestMatchers("/actuator/**").permitAll()
                        // 创建用户接口放行（注册接口，不需要认证）
                        .requestMatchers("/api/users/save").permitAll()
                        // 获取 Token 接口放行（用于测试和开发）
                        .requestMatchers("/api/auth/token").permitAll()
                        // Keycloak 事件回调（内部使用，使用共享密钥校验）
                        .requestMatchers("/internal/keycloak/events/**").permitAll()
                        // 其他请求需要认证
                        .anyRequest().authenticated()
                )
                
                // OAuth2 Resource Server（JWT 验证）
                .oauth2ResourceServer(oauth2 -> oauth2.jwt());

        return http.build();
    }
}

