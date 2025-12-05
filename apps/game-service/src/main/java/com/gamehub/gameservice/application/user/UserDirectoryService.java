package com.gamehub.gameservice.application.user;

import com.gamehub.gameservice.infrastructure.client.system.SystemUserClient;
import com.gamehub.web.common.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 用户目录服务（游戏域调用用户域的统一入口）。
 * 通过 Feign Client 调用 system-service，获取用户详细信息，并在此处统一做熔断/兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final SystemUserClient systemUserClient;

    /**
     * 批量按 Keycloak 用户ID 查询用户档案。
     *
     * @param userIds Keycloak 用户ID列表
     * @return 用户档案列表（失败时返回空列表，不抛异常）
     */
    @CircuitBreaker(name = "systemUserClient", fallbackMethod = "fallbackUserInfos")
    public List<UserProfileView> getUserInfos(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        ApiResponse<List<UserProfileView>> resp = systemUserClient.getUserInfos(userIds);
        if (resp == null || resp.code() != 200 || resp.data() == null) {
            log.warn("获取用户信息失败: userIds={}, response={}", userIds, resp);
            return Collections.emptyList();
        }
        return resp.data();
    }

    /**
     * 熔断 / 超时 / 远程异常时的兜底逻辑。
     */
    @SuppressWarnings("unused")
    private List<UserProfileView> fallbackUserInfos(List<String> userIds, Throwable ex) {
        log.warn("调用 system-service 失败，走兜底: userIds={}, ex={}", userIds, ex.toString());
        return Collections.emptyList();
    }

    /**
     * 根据单个 Keycloak 用户ID 获取用户档案。
     *
     * @param userId Keycloak 用户ID
     * @return 用户档案，不存在或调用失败时返回 null
     */
    public UserProfileView getUserInfo(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        List<UserProfileView> list = getUserInfos(List.of(userId));
        return list.isEmpty() ? null : list.get(0);
    }
}

