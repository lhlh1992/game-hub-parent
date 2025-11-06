package com.gamehub.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * WebSocket Token 过滤器：
 * 从 URL 参数中提取 access_token，并添加到请求头中，用于 WebSocket 握手认证。
 * 
 * 原因：SockJS 的 HTTP 握手请求无法在请求头中传递自定义 header，但可以通过 URL 参数传递 token。
 * 此过滤器将 URL 参数中的 token 提取出来，放入 Authorization header，供 Gateway 的认证机制使用。
 */
@Component
public class WebSocketTokenFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 仅处理 WebSocket 相关的请求
        String path = exchange.getRequest().getURI().getPath();
        if (!path.contains("/ws/")) {
            return chain.filter(exchange);
        }

        // 如果请求头中已存在 Authorization，直接放行
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && !authHeader.isBlank()) {
            return chain.filter(exchange);
        }

        // 从 URL 参数中提取 access_token
        String token = exchange.getRequest().getQueryParams().getFirst("access_token");
        if (token != null && !token.isBlank()) {
            // 将 token 添加到请求头中
            ServerWebExchange mutated = exchange.mutate()
                    .request(builder -> builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .build();
            return chain.filter(mutated);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 在 TokenRelay 之前执行，确保 token 能被正确提取
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}

