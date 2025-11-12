package com.gamehub.gateway.config;

import com.gamehub.gateway.service.JwtBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * 网关使用的 JWT 解码器：
 * - 先检查黑名单，再委托默认的 Nimbus 解码器完成验签；
 * - 命中黑名单则直接抛出异常，让 Spring Security 返回 401。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwtDecoderConfig {

    private final JwtBlacklistService jwtBlacklistService;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        ReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
        return token -> jwtBlacklistService.isBlacklisted(token)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.warn("JWT 命中黑名单，拒绝访问。token={}...", shorten(token));
                        return Mono.error(new JwtException("Token has been revoked"));
                    }
                    return delegate.decode(token)
                            .doOnSuccess(jwt -> log.debug("JWT 验证通过，sub={}, token={}...", jwt.getSubject(), shorten(token)));
                });
    }

    private String shorten(String token) {
        if (token == null) {
            return "null";
        }
        int length = token.length();
        return length <= 10 ? token : token.substring(0, 5) + "..." + token.substring(length - 5);
    }
}

