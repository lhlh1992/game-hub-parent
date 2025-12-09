package com.gamehub.chatservice.infrastructure.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 调用 system-service 获取用户信息/禁言状态。
 */
@FeignClient(name = "system-service", fallback = SystemUserClientFallback.class)
public interface SystemUserClient {

    @GetMapping("/api/users/{userId}/info")
    @CircuitBreaker(name = "systemUserClient")
    UserInfo getUserInfo(@PathVariable String userId);

    @GetMapping("/api/users/{userId}/muted")
    @CircuitBreaker(name = "systemUserClient")
    boolean isMuted(@PathVariable String userId);

    record UserInfo(String userId, String username, String nickname, String avatarUrl, String email) {}
}

