package com.gamehub.gameservice.platform.ws;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * WebSocket STOMP 认证拦截器
 * 
 * 在 STOMP CONNECT 阶段验证 JWT token 并设置用户身份，供后续消息处理使用。
 * 仅处理 CONNECT 命令，其他消息直接放行。
 * 验证失败时不设置用户身份，后续操作会因缺少用户而失败。
 */
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();

    public WebSocketAuthChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * 拦截入站消息，在 CONNECT 阶段进行认证
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        
        // 仅处理 CONNECT 命令
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            // 从 header 提取 token（支持 Authorization 或 access_token）
            String auth = firstHeader(accessor, "Authorization");
            if (auth == null) auth = firstHeader(accessor, "authorization");
            if (auth == null) {
                String tokenOnly = firstHeader(accessor, "access_token");
                if (tokenOnly != null && !tokenOnly.isBlank()) auth = "Bearer " + tokenOnly.trim();
            }
            
            // 验证 token 并设置用户身份
            if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
                String token = auth.substring(7).trim();
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
                    String name = Objects.requireNonNullElse(
                            jwt.getClaimAsString("preferred_username"), 
                            jwt.getSubject()
                    );
                    accessor.setUser(new JwtAuthenticationToken(jwt, authorities, name));
                } catch (Exception ignore) {
                    // 验证失败不设置用户，后续操作会因缺少用户而失败
                }
            }
        }
        return message;
    }

    /**
     * 从 STOMP header 中提取指定 key 的第一个值
     */
    private static String firstHeader(StompHeaderAccessor accessor, String key) {
        List<String> vals = accessor.getNativeHeader(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
}


