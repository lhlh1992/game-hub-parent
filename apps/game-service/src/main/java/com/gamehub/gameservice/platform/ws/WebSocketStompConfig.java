package com.gamehub.gameservice.platform.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket + STOMP 配置类
 * ----------------------------------------
 * 本类启用 Spring 的消息代理（Message Broker），
 * 允许客户端通过 /ws 端点连接 WebSocket，
 * 并使用 /app 前缀发送消息、/topic 前缀订阅广播。
 *
 * 用途：
 *   - /app/... : 客户端发送（如 /app/gomoku.place）
 *   - /topic/... : 服务端广播（如 /topic/room.{roomId}）
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketStompConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthChannelInterceptor authInterceptor;

    public WebSocketStompConfig(WebSocketAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 注册 WebSocket STOMP 端点
     * ----------------------------------------
     * 前端连接地址：
     *   ws://localhost:8081/ws
     *   或 SockJS 备用: http://localhost:8081/ws
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 客户端连接入口：/ws
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*"); // 允许跨域访问（开发时用 *，生产建议限制域名）
        // 可选：SockJS 回退（防止浏览器不支持原生 WebSocket）
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS(); //启用 SockJS 兼容层，让旧浏览器也能用
    }

    /**
     * 配置消息代理（Broker）消息路由规则
     * ----------------------------------------
     * 设置消息前缀：
     *   - enableSimpleBroker(): 启用内存消息代理，用于广播订阅
     *   - setApplicationDestinationPrefixes(): 指定客户端发消息的前缀
     *   - setUserDestinationPrefix(): 用户点对点通信的前缀（可选）
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 服务器推送给订阅者的目的地前缀：/topic、/queue
        registry.enableSimpleBroker("/topic", "/queue");
        // 设置应用消息前缀：客户端发往服务端时要带上 /app
        registry.setApplicationDestinationPrefixes("/app");
        //  设置用户点对点消息前缀（服务端 -> 当前用户）
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * 为客户端入站 STOMP 通道添加拦截器。
     * 用于在 CONNECT / SUBSCRIBE / SEND 时做认证与权限校验。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
