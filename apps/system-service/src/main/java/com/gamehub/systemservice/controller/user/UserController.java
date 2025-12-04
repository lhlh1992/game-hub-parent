package com.gamehub.systemservice.controller.user;

import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.dto.request.CreateUserRequest;
import com.gamehub.systemservice.dto.request.UpdateUserRequest;
import com.gamehub.systemservice.dto.response.UserInfo;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.service.user.UserService;
import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户的基础信息（仅 sys_user）
     * 保留历史接口，主要用于内部调试/兼容
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SysUser>> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String userId = CurrentUserHelper.getUserId(jwt);
        UUID keycloakUserId = UUID.fromString(userId);

        Optional<SysUser> user = userService.findByKeycloakUserId(keycloakUserId);
        if (user.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("用户不存在，请先同步用户信息"));
        }

        return ResponseEntity.ok(ApiResponse.success(user.get()));
    }

    /**
     * 获取当前登录用户的完整信息（sys_user + sys_user_profile + 预留游戏信息）
     */
    @GetMapping("/me/full")
    public ResponseEntity<ApiResponse<UserInfo>> getCurrentUserFull(@AuthenticationPrincipal Jwt jwt) {
        String userId = CurrentUserHelper.getUserId(jwt); // Keycloak 用户 ID（String 格式，对应 sub）

        java.util.List<UserInfo> userInfos = userService.findUserInfosByKeycloakUserIds(java.util.List.of(userId));
        if (userInfos.isEmpty()) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("用户不存在，请先同步用户信息"));
        }
        return ResponseEntity.ok(ApiResponse.success(userInfos.get(0)));
    }

    /**
     * 根据用户ID查询
     */
    @GetMapping("/get/{userId}")
    public Result<SysUser> getUserById(@PathVariable UUID userId) {
        Optional<SysUser> user = userService.findById(userId);
        if (user.isEmpty()) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(user.get());
    }

    /**
     * 同步用户（从 Keycloak 同步到系统用户表）
     * 通常在用户首次登录时调用
     */
    @PostMapping("/sync")
    public Result<SysUser> syncUser(@AuthenticationPrincipal Jwt jwt) {
        // 从 JWT 中获取用户信息
        var userInfo = CurrentUserHelper.from(jwt);
        UUID keycloakUserId = UUID.fromString(userInfo.userId());
        String username = userInfo.username();
        String email = userInfo.email();

        if (username == null || username.isBlank()) {
            return Result.error(400, "JWT 中缺少 preferred_username");
        }

        try {
            SysUser user = userService.syncUser(keycloakUserId, username, email);
            return Result.success("用户同步成功", user);
        } catch (Exception e) {
            log.error("同步用户失败", e);
            return Result.error(500, "同步用户失败: " + e.getMessage());
        }
    }

    /**
     * 根据用户名查询
     */
    @GetMapping("/username/{username}")
    public Result<SysUser> getUserByUsername(@PathVariable String username) {
        Optional<SysUser> user = userService.findByUsername(username);
        if (user.isEmpty()) {
            return Result.error(404, "用户不存在");
        }
        return Result.success(user.get());
    }

    /**
     * 创建用户（同时在 Keycloak 和系统数据库中创建）
     */
    @PostMapping("/save")
    public Result<SysUser> createUser(@Valid @RequestBody CreateUserRequest request) {
        try {
            SysUser user = userService.createUser(
                    request.getUsername(),
                    request.getNickname(),
                    request.getPassword(),
                    request.getEmail()
            );
            return Result.success("用户创建成功", user);
        } catch (Exception e) {
            log.error("创建用户失败", e);
            return Result.error(500, "创建用户失败: " + e.getMessage());
        }
    }

    /**
     * 更新用户信息（昵称、密码、邮箱）
     */
    @PutMapping("/update/{userId}")
    public Result<SysUser> updateUser(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRequest request) {
        try {
            SysUser user = userService.updateUser(
                    userId,
                    request.getNickname(),
                    request.getPassword(),
                    request.getEmail()
            );
            return Result.success("用户更新成功", user);
        } catch (Exception e) {
            log.error("更新用户失败: userId={}", userId, e);
            return Result.error(500, "更新用户失败: " + e.getMessage());
        }
    }

    /**
     * 删除用户（软删除）
     */
    @DeleteMapping("/delete/{userId}")
    public Result<Void> deleteUser(@PathVariable UUID userId) {
        try {
            userService.deleteUser(userId);
            return Result.success("用户删除成功", null);
        } catch (Exception e) {
            log.error("删除用户失败: userId={}", userId, e);
            return Result.error(500, "删除用户失败: " + e.getMessage());
        }
    }

    /**
     * 批量获取用户信息
     * 
     * @param userIds Keycloak 用户ID列表（String 格式，对应 JWT 中的 sub）
     * @return 用户信息列表
     */
    @PostMapping("/users/batch")
    public ResponseEntity<ApiResponse<List<UserInfo>>> getUserInfos(@RequestBody List<String> userIds) {
        try {
            List<UserInfo> userInfos = userService.findUserInfosByKeycloakUserIds(userIds);
            return ResponseEntity.ok(ApiResponse.success(userInfos));
        } catch (Exception e) {
            log.error("批量获取用户信息失败: userIds={}", userIds, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.serverError("批量获取用户信息失败: " + e.getMessage()));
        }
    }

    /**
     * 根据单个 Keycloak 用户ID获取用户信息
     * 
     * @param userId Keycloak 用户ID（String 格式）
     * @return 用户信息
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<UserInfo>> getUserInfo(@PathVariable String userId) {
        try {
            List<UserInfo> userInfos = userService.findUserInfosByKeycloakUserIds(List.of(userId));
            if (userInfos.isEmpty()) {
                return ResponseEntity.status(404)
                        .body(ApiResponse.notFound("用户不存在: " + userId));
            }
            return ResponseEntity.ok(ApiResponse.success(userInfos.get(0)));
        } catch (Exception e) {
            log.error("获取用户信息失败: userId={}", userId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.serverError("获取用户信息失败: " + e.getMessage()));
        }
    }

    /**
     * 批量获取用户信息（兼容旧接口，重定向到新接口）
     * @deprecated 使用 /users/batch 替代
     */
    @Deprecated
    @PostMapping("/players/batch")
    public ResponseEntity<ApiResponse<List<UserInfo>>> getPlayerInfos(@RequestBody List<String> userIds) {
        return getUserInfos(userIds);
    }

    /**
     * 根据单个 Keycloak 用户ID获取用户信息（兼容旧接口，重定向到新接口）
     * @deprecated 使用 /users/{userId} 替代
     */
    @Deprecated
    @GetMapping("/players/{userId}")
    public ResponseEntity<ApiResponse<UserInfo>> getPlayerInfo(@PathVariable String userId) {
        return getUserInfo(userId);
    }
}

