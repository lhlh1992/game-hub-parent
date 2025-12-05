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
 * 
 * 使用 Spring Cloud LoadBalancer 实现服务发现与负载均衡。
 * 本地开发通过配置文件指定服务地址，Docker Compose 通过容器 DNS 解析服务名，
 * Kubernetes 环境会自动从 K8s API 获取服务实例并实现负载均衡。
 */
@FeignClient(
        name = "system-service",  // 服务名，LoadBalancer 会根据此名称查找服务实例
        path = "/api/users"       // 统一路径前缀，对应 UserController 上的 @RequestMapping
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



