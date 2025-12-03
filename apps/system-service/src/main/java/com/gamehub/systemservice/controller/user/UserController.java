package com.gamehub.systemservice.controller.user;

import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.dto.request.CreateUserRequest;
import com.gamehub.systemservice.dto.request.UpdateUserRequest;
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
     * 获取当前登录用户信息
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
}

