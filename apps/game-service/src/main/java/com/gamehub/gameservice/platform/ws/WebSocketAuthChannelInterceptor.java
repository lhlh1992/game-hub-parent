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
 * 在 STOMP CONNECT 阶段从客户端头部读取 Bearer Token，
 * 验证后将用户设置到连接的 Principal，供后续消息处理使用。
 */
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();

    public WebSocketAuthChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String auth = firstHeader(accessor, "Authorization");
            if (auth == null) auth = firstHeader(accessor, "authorization");
            if (auth == null) {
                // 兼容纯 token 传法：access_token 头
                String tokenOnly = firstHeader(accessor, "access_token");
                if (tokenOnly != null && !tokenOnly.isBlank()) auth = "Bearer " + tokenOnly.trim();
            }
            if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
                String token = auth.substring(7).trim();
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
                    String name = Objects.requireNonNullElse(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
                    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, name);
                    accessor.setUser(authentication);
                } catch (Exception ignore) {
                    // 验证失败不设置用户，后续控制器会因缺少用户而拒绝敏感操作
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


