package com.gamehub.systemservice.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.dto.request.UpdateProfileRequest;
import com.gamehub.systemservice.dto.response.UserInfo;
import com.gamehub.systemservice.service.user.UserService;
import com.gamehub.web.common.CurrentUserHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 用户资料控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    /**
     * 获取当前用户的完整信息
     */
    @GetMapping("/profile")
    public ResponseEntity<com.gamehub.web.common.ApiResponse<UserInfo>> getProfile(
            @AuthenticationPrincipal Jwt jwt) {
        String userId = CurrentUserHelper.getUserId(jwt);
        java.util.List<UserInfo> userInfos = userService.findUserInfosByKeycloakUserIds(java.util.List.of(userId));
        if (userInfos.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(com.gamehub.web.common.ApiResponse.notFound("用户不存在"));
        }
        return ResponseEntity.ok(com.gamehub.web.common.ApiResponse.success(userInfos.get(0)));
    }

    /**
     * 更新当前用户资料
     * 幂等性：相同参数多次调用，结果一致
     */
    @PutMapping("/profile")
    public ResponseEntity<com.gamehub.web.common.ApiResponse<UserInfo>> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {
        try {
            String userId = CurrentUserHelper.getUserId(jwt);

            // 解析 settings JSON 字符串
            java.util.Map<String, Object> settings = null;
            if (request.getSettings() != null && !request.getSettings().isBlank()) {
                try {
                    settings = objectMapper.readValue(request.getSettings(), 
                            new TypeReference<java.util.Map<String, Object>>() {});
                } catch (Exception e) {
                    log.warn("解析 settings JSON 失败: {}", request.getSettings(), e);
                    return ResponseEntity.status(400)
                            .body(com.gamehub.web.common.ApiResponse.badRequest("settings 格式不正确"));
                }
            }

            UserInfo updatedUser = userService.updateProfile(
                    userId,
                    request.getNickname(),
                    request.getEmail(),
                    request.getPhone(),
                    request.getAvatarUrl(),
                    request.getBio(),
                    request.getLocale(),
                    request.getTimezone(),
                    settings
            );

            return ResponseEntity.ok(com.gamehub.web.common.ApiResponse.success(updatedUser));
        } catch (com.gamehub.systemservice.exception.BusinessException e) {
            log.warn("更新用户资料失败: {}", e.getMessage());
            return ResponseEntity.status(400)
                    .body(com.gamehub.web.common.ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("更新用户资料失败", e);
            return ResponseEntity.status(500)
                    .body(com.gamehub.web.common.ApiResponse.serverError("更新用户资料失败: " + e.getMessage()));
        }
    }
}

