package com.gamehub.web.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 客户端鉴权自动配置。
 *
 * 自动从当前请求中提取 JWT Token，并添加到 Feign 请求的 Authorization Header 中。
 * 支持三种方式：
 * 1. 从 HTTP 请求 Header 中获取（适用于 HTTP 请求场景）
 * 2. 从 ThreadLocal 获取（适用于 WebSocket 消息处理等场景）
 * 3. 从 SecurityContext 中获取（备用方案）
 *
 * 使用条件：当项目中存在 OpenFeign 依赖时自动启用。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
public class FeignAuthAutoConfiguration {

    @Bean
    public RequestInterceptor feignRequestInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                String authorization = null;

                // 方式1：从 HTTP 请求 Header 中获取（适用于 HTTP 请求场景）
                ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    authorization = request.getHeader("Authorization");
                }

                // 方式2：如果方式1未获取到，从 ThreadLocal 获取（适用于 WebSocket 消息处理场景）
                if (authorization == null || authorization.isBlank()) {
                    String token = JwtTokenHolder.getToken();
                    if (token != null && !token.isBlank()) {
                        authorization = "Bearer " + token;
                    }
                }

                // 方式3：如果前两种方式都未获取到，从 SecurityContext 中获取（备用方案）
                if (authorization == null || authorization.isBlank()) {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                        Jwt jwt = jwtAuth.getToken();
                        if (jwt != null) {
                            try {
                                String tokenValue = jwt.getTokenValue();
                                if (tokenValue != null && !tokenValue.isBlank()) {
                                    authorization = "Bearer " + tokenValue;
                                }
                            } catch (Exception e) {
                                // 无法从 Jwt 对象获取 token 值
                            }
                        }
                    }
                }

                // 如果获取到了 Authorization Header，添加到 Feign 请求中
                if (authorization != null && !authorization.isBlank()) {
                    template.header("Authorization", authorization);
                } else {
                    log.error("无法获取 JWT Token，Feign 调用将不携带 Token（会导致 401 错误）, url={}", template.url());
                }
            }
        };
    }
}

