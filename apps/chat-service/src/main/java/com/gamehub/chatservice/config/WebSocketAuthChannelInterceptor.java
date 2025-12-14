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
                    
                    // 关键修复：使用 sid（loginSessionId）作为 key，而不是 WebSocket sessionId
                    // 因为 sid 在整个登录生命周期内不变，即使 token 刷新也不会变
                    // 这样 token 刷新时，可以覆盖旧的 token，而不是创建新的 key
                    String loginSessionId = extractLoginSessionId(jwt);
                    String wsSessionId = accessor.getSessionId();
                    
                    // 策略：同时保存两个 key（双重保障）
                    // 1. 使用 loginSessionId 作为主要 key（支持 token 刷新）
                    // 2. 使用 wsSessionId 作为备用 key（向后兼容，当 loginSessionId 为 null 时使用）
                    boolean saved = false;
                    if (loginSessionId != null && !loginSessionId.isBlank()) {
                        tokenStore.putToken(loginSessionId, token);
                        saved = true;
                    }
                    
                    if (wsSessionId != null) {
                        tokenStore.putToken(wsSessionId, token);
                    } else {
                        if (!saved) {
                            log.error("CONNECT 时无法获取 loginSessionId 或 sessionId，无法保存 token");
                        }
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
            // 关键修复：优先使用 loginSessionId（sid）获取 token，因为 token 刷新时 sid 不变
            
            // 获取 WebSocket sessionId
            String wsSessionId = accessor.getSessionId();
            if (wsSessionId == null) {
                Object sessionIdObj = message.getHeaders().get("simpSessionId");
                if (sessionIdObj != null) {
                    wsSessionId = sessionIdObj.toString();
                }
            }
            
            // 首先尝试从 accessor.getUser() 中提取 loginSessionId（sid）
            String loginSessionId = null;
            if (accessor.getUser() instanceof JwtAuthenticationToken jwtAuth) {
                SecurityContext securityContext = new SecurityContextImpl();
                securityContext.setAuthentication(jwtAuth);
                SecurityContextHolder.setContext(securityContext);
                
                // 从 JWT 中提取 loginSessionId（sid）
                Jwt jwt = jwtAuth.getToken();
                loginSessionId = extractLoginSessionId(jwt);
            }
            
            // 关键修复：优先使用 loginSessionId 获取 token（因为 token 刷新时 sid 不变）
            String token = null;
            if (loginSessionId != null && !loginSessionId.isBlank()) {
                token = tokenStore.getToken(loginSessionId);
                if (token != null && !token.isBlank()) {
                    JwtTokenHolder.setToken(token);
                }
            }
            
            // 降级：如果使用 loginSessionId 获取失败，尝试使用 WebSocket sessionId
            if ((token == null || token.isBlank()) && wsSessionId != null) {
                token = tokenStore.getToken(wsSessionId);
                if (token != null && !token.isBlank()) {
                    JwtTokenHolder.setToken(token);
                    
                    // 如果从 wsSessionId 获取到 token，尝试从 token 中提取 loginSessionId 并更新存储
                    try {
                        Jwt jwt = jwtDecoder.decode(token);
                        String extractedLoginSessionId = extractLoginSessionId(jwt);
                        if (extractedLoginSessionId != null && !extractedLoginSessionId.isBlank()) {
                            // 使用 loginSessionId 重新保存 token（确保后续查询能使用 loginSessionId）
                            tokenStore.putToken(extractedLoginSessionId, token);
                        }
                    } catch (Exception e) {
                        // 忽略提取失败
                    }
                }
            }
            
            // 如果获取到 token，设置到 ThreadLocal 和 SecurityContext
            if (token != null && !token.isBlank()) {
                // 如果 accessor.getUser() 不存在，但 token 存在，尝试从 token 恢复用户信息
                if (accessor.getUser() == null) {
                    try {
                        Jwt jwt = jwtDecoder.decode(token);
                        Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
                        String name = Objects.requireNonNullElse(
                                jwt.getClaimAsString("preferred_username"),
                                jwt.getSubject()
                        );
                        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, name);
                        accessor.setUser(authentication);
                        
                        SecurityContext securityContext = new SecurityContextImpl();
                        securityContext.setAuthentication(authentication);
                        SecurityContextHolder.setContext(securityContext);
                    } catch (Exception e) {
                        // 忽略恢复失败
                    }
                }
            } else {
                String userName = accessor.getUser() != null ? accessor.getUser().getName() : "unknown";
                log.error("存储中没有 token, loginSessionId={}, wsSessionId={}, user={}", 
                        loginSessionId, wsSessionId, userName);
            }
        }
        return message;
    }

    private static String firstHeader(StompHeaderAccessor accessor, String key) {
        List<String> vals = accessor.getNativeHeader(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
    
    /**
     * 从 JWT 中提取 loginSessionId（sid）。
     * 优先使用 sid，如果没有则尝试使用 session_state（向后兼容）。
     */
    private String extractLoginSessionId(Jwt jwt) {
        // 优先使用 sid
        Object sidObj = jwt.getClaim("sid");
        if (sidObj != null) {
            String sid = sidObj.toString();
            if (sid != null && !sid.isBlank()) {
                return sid;
            }
        }
        
        // 如果没有 sid，尝试使用 session_state（向后兼容）
        Object sessionStateObj = jwt.getClaim("session_state");
        if (sessionStateObj != null) {
            String sessionState = sessionStateObj.toString();
            if (sessionState != null && !sessionState.isBlank()) {
                return sessionState;
            }
        }
        
        return null;
    }
}

