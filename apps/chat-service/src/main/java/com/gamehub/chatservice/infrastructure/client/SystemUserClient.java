package com.gamehub.chatservice.infrastructure.client;

import com.gamehub.web.common.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 调用 system-service 获取用户信息/禁言状态/好友关系。
 */
@FeignClient(name = "system-service", fallback = SystemUserClientFallback.class)
public interface SystemUserClient {

    @GetMapping("/api/users/{userId}/info")
    @CircuitBreaker(name = "systemUserClient")
    UserInfo getUserInfo(@PathVariable String userId);

    @GetMapping("/api/users/{userId}/muted")
    @CircuitBreaker(name = "systemUserClient")
    boolean isMuted(@PathVariable String userId);

    /**
     * 检查两个用户是否是好友关系
     * 注意：system-service 返回的是 ApiResponse<Boolean>，需要提取 data 字段
     *
     * @param userId1 用户1的Keycloak用户ID（String格式）
     * @param userId2 用户2的Keycloak用户ID（String格式）
     * @return ApiResponse包装的布尔值，需要提取 data 字段
     */
    @GetMapping("/api/friends/check/{userId1}/{userId2}")
    @CircuitBreaker(name = "systemUserClient")
    ApiResponse<Boolean> isFriend(@PathVariable String userId1, @PathVariable String userId2);

    record UserInfo(String userId, String username, String nickname, String avatarUrl, String email) {}
}

