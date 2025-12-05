package com.gamehub.gameservice.platform.transport;

import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import com.gamehub.web.common.CurrentUserInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 当前用户信息查询接口
 * 返回当前登录用户的基本信息（从 JWT Token 中提取）
 */
@RestController
@RequestMapping("/me")
public class MeController {

    /**
     * 获取当前登录用户信息
     * @param jwt JWT Token（Spring Security 自动注入）
     * @return 用户信息（userId、username、nickname、email、角色等）
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUserInfo user = CurrentUserHelper.from(jwt);
        
        Map<String, Object> body = Map.of(
                "sub", user.userId(),
                "username", user.username(),
                "nickname", user.nickname() != null ? user.nickname() : "",
                "displayName", user.getDisplayName(),
                "email", user.email() != null ? user.email() : "",
                "realm_roles", user.realmRoles(),
                "resource_access", user.clientRoles()
        );

        return ResponseEntity.ok(ApiResponse.success(body));
    }
}

