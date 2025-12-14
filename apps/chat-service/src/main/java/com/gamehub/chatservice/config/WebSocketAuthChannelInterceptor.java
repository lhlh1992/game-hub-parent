package com.gamehub.chatservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import com.gamehub.web.common.feign.JwtTokenHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 参考 game-service：在 CONNECT 阶段从 STOMP header 提取 token，解码后设置用户。
 * 后续业务鉴权（禁言/成员/频控）在业务层处理。
 */
@Component
@Slf4j
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
    private final WebSocketTokenStore tokenStore;

    public WebSocketAuthChannelInterceptor(JwtDecoder jwtDecoder, WebSocketTokenStore tokenStore) {
        this.jwtDecoder = jwtDecoder;
        this.tokenStore = tokenStore;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        // 注意：不要在这里清理 ThreadLocal，因为 Feign 调用可能还在进行中
        // ThreadLocal 会在下一个消息处理时被覆盖，或者在连接断开时清理
        // 如果需要清理，应该在消息处理完成后（如 @MessageMapping 方法返回后）清理
        // 但为了安全，我们不在拦截器中清理，而是在 WebSocketSessionManager 中清理
    }
    
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.info("WS CONNECT received (chat-service), sessionId={}", accessor.getSessionId());
            
            // 方式1：从 STOMP header 中获取（客户端在 CONNECT 时传递）
            String auth = firstHeader(accessor, "Authorization");
            if (auth == null) auth = firstHeader(accessor, "authorization");
            if (auth == null) {
                String tokenOnly = firstHeader(accessor, "access_token");
                if (tokenOnly != null && !tokenOnly.isBlank()) auth = "Bearer " + tokenOnly.trim();
            }
            
            // 方式2：如果 STOMP header 中没有，尝试从 HTTP 请求头中获取（gateway 过滤器放入的）
            if ((auth == null || auth.isBlank())) {
                try {
                    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attributes != null) {
                        jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
                        if (request != null) {
                            auth = request.getHeader("Authorization");
                            if (auth == null || auth.isBlank()) {
                                auth = request.getHeader("authorization");
                            }
                            // 从 HTTP 请求头获取到 token（gateway 过滤器放入的）
                        }
                    }
                } catch (Exception e) {
                    // 无法从 HTTP 请求上下文获取 token
                }
            }

            if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
                String token = auth.substring(7).trim();
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
                    String name = Objects.requireNonNullElse(
                            jwt.getClaimAsString("preferred_username"),
                            jwt.getSubject()
                    );
                    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, name);
                    accessor.setUser(authentication);
                    
                    // 保存原始 token 到存储（以 sessionId 为 key）
                    String sessionId = accessor.getSessionId();
                    if (sessionId != null) {
                        tokenStore.putToken(sessionId, token);
                    }
                    
                    // 保存原始 token 到 ThreadLocal，供 Feign 调用时使用
                    JwtTokenHolder.setToken(token);
                    
                    // 同时设置到 SecurityContext
                    SecurityContext securityContext = new SecurityContextImpl();
                    securityContext.setAuthentication(authentication);
                    SecurityContextHolder.setContext(securityContext);
                    
                    log.info("WS CONNECT auth ok (chat-service), user={}, sessionId={}", name, accessor.getSessionId());
                } catch (Exception ignore) {
                    // 验证失败不设置用户，后续操作会因缺少用户而失败
                    log.warn("WS CONNECT auth failed (chat-service), sessionId={}", accessor.getSessionId());
                }
            } else {
                log.warn("WS CONNECT missing bearer token (chat-service), sessionId={}", accessor.getSessionId());
            }
        } else {
            // 对于非 CONNECT 消息（如 SEND 消息），从已建立的会话中获取用户身份并设置到 SecurityContext
            if (accessor.getUser() instanceof JwtAuthenticationToken jwtAuth) {
                SecurityContext securityContext = new SecurityContextImpl();
                securityContext.setAuthentication(jwtAuth);
                SecurityContextHolder.setContext(securityContext);
                
                // 从存储中获取原始 token（在 CONNECT 时保存的）
                String sessionId = accessor.getSessionId();
                
                // 如果方式1失败，从消息 header 中获取（Spring WebSocket 会将 sessionId 存储在 header 中）
                if (sessionId == null) {
                    Object sessionIdObj = message.getHeaders().get("simpSessionId");
                    if (sessionIdObj != null) {
                        sessionId = sessionIdObj.toString();
                    }
                }
                
                if (sessionId != null) {
                    String token = tokenStore.getToken(sessionId);
                    if (token != null && !token.isBlank()) {
                        JwtTokenHolder.setToken(token);
                    } else {
                        log.warn("存储中没有 token, sessionId={}, 可能原因：1) Redis 连接失败 2) token 已过期 3) sessionId 不匹配", sessionId);
                    }
                } else {
                    log.warn("无法获取 sessionId, 无法从存储中获取 token, user={}", jwtAuth.getName());
                }
            }
        }
        return message;
    }

    private static String firstHeader(StompHeaderAccessor accessor, String key) {
        List<String> vals = accessor.getNativeHeader(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
}

