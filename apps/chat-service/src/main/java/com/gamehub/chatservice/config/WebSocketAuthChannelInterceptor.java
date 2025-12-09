package com.gamehub.chatservice.config;

import lombok.extern.slf4j.Slf4j;
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
 * 参考 game-service：在 CONNECT 阶段从 STOMP header 提取 token，解码后设置用户。
 * 后续业务鉴权（禁言/成员/频控）在业务层处理。
 */
@Component
@Slf4j
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
            log.info("WS CONNECT received (chat-service), sessionId={}", accessor.getSessionId());
            String auth = firstHeader(accessor, "Authorization");
            if (auth == null) auth = firstHeader(accessor, "authorization");
            if (auth == null) {
                String tokenOnly = firstHeader(accessor, "access_token");
                if (tokenOnly != null && !tokenOnly.isBlank()) auth = "Bearer " + tokenOnly.trim();
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
                    accessor.setUser(new JwtAuthenticationToken(jwt, authorities, name));
                    log.info("WS CONNECT auth ok (chat-service), user={}, sessionId={}", name, accessor.getSessionId());
                } catch (Exception ignore) {
                    // 验证失败不设置用户，后续操作会因缺少用户而失败
                    log.warn("WS CONNECT auth failed (chat-service), sessionId={}", accessor.getSessionId());
                }
            } else {
                log.warn("WS CONNECT missing bearer token (chat-service), sessionId={}", accessor.getSessionId());
            }
        }
        return message;
    }

    private static String firstHeader(StompHeaderAccessor accessor, String key) {
        List<String> vals = accessor.getNativeHeader(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
}

