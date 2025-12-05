package com.gamehub.gameservice.infrastructure.client.system;

import com.gamehub.gameservice.application.user.UserProfileView;
import com.gamehub.web.common.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 调用 system-service 用户接口的统一 Feign Client。
 * 说明：
 *  - name 仅用于日志/度量；url 通过配置 system-service.url 注入，兼容本地 / Docker / K8s。
 *  - path 固定为 /api/users，对应 UserController 上的 @RequestMapping。
 */
@FeignClient(
        name = "system-service",
        url = "${system-service.url}",
        path = "/api/users"
)
public interface SystemUserClient {

    /**
     * 批量按 Keycloak 用户ID（JWT sub）查询用户档案。
     */
    @PostMapping("/users/batch")
    ApiResponse<List<UserProfileView>> getUserInfos(@RequestBody List<String> userIds);

    /**
     * 单个按 Keycloak 用户ID 查询用户档案。
     */
    @GetMapping("/users/{userId}")
    ApiResponse<UserProfileView> getUserInfo(@PathVariable("userId") String userId);

    /**
     * 当前登录用户完整档案（用于 /me 场景，也方便 game-service 自己调用）。
     */
    @GetMapping("/me/full")
    ApiResponse<UserProfileView> getCurrentUserFull();
}



