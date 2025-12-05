package com.gamehub.web.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 客户端鉴权自动配置。
 * 
 * 自动从当前请求中提取 JWT Token，并添加到 Feign 请求的 Authorization Header 中。
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

                // 如果获取到了 Authorization Header，添加到 Feign 请求中
                if (authorization != null && !authorization.isBlank()) {
                    template.header("Authorization", authorization);
                    log.debug("Feign 请求已添加 JWT Token");
                } else {
                    log.warn("无法获取 JWT Token，Feign 调用将不携带 Token（可能导致 401 错误）");
                }
            }
        };
    }
}

