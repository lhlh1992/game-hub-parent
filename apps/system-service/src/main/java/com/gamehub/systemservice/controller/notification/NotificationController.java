package com.gamehub.systemservice.controller.notification;

import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.entity.user.SysUser;
import com.gamehub.systemservice.service.notification.NotificationService;
import com.gamehub.systemservice.service.notification.dto.NotificationTypeMetadata;
import com.gamehub.systemservice.service.notification.dto.NotificationView;
import com.gamehub.systemservice.service.user.UserService;
import com.gamehub.web.common.ApiResponse;
import com.gamehub.web.common.CurrentUserHelper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * 全局通知中心接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final UserService userService;

    private UUID resolveSystemUserId(Jwt jwt) {
        String keycloakUserId = CurrentUserHelper.getUserId(jwt);
        Optional<SysUser> user = userService.findByKeycloakUserId(UUID.fromString(keycloakUserId));
        return user.map(SysUser::getId).orElse(null);
    }

    /**
     * 查询通知列表（默认未读，limit=20）。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationView>>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "10") @Min(1) @Max(100) int limit
    ) {
        UUID userId = resolveSystemUserId(jwt);
        if (userId == null) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("用户不存在"));
        }
        List<NotificationView> list = notificationService.listNotifications(userId, status, limit);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * 未读数量。
     */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Long>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = resolveSystemUserId(jwt);
        if (userId == null) {
            return ResponseEntity.status(404).body(ApiResponse.notFound("用户不存在"));
        }
        long count = notificationService.countUnread(userId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    /**
     * 单条标记已读。
     */
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        UUID userId = resolveSystemUserId(jwt);
        if (userId == null) {
            return Result.error(404, "用户不存在");
        }
        notificationService.markRead(userId, id);
        return Result.success();
    }

    /**
     * 全部标记已读。
     */
    @PostMapping("/read-all")
    public Result<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = resolveSystemUserId(jwt);
        if (userId == null) {
            return Result.error(404, "用户不存在");
        }
        notificationService.markAllRead(userId);
        return Result.success();
    }

    /**
     * 返回支持的通知类型元数据，便于前端展示/提示。
     * 说明：实际业务动作仍由前端内置处理，不在此返回可执行代码，避免后端配置驱动前端行为过重。
     */
    @GetMapping("/metadata")
    public ResponseEntity<ApiResponse<List<NotificationTypeMetadata>>> metadata() {
        List<NotificationTypeMetadata> list = List.of(
                NotificationTypeMetadata.builder()
                        .type("FRIEND_REQUEST")
                        .actionable(true)
                        .actions(List.of("ACCEPT", "REJECT"))
                        .description("好友申请，可同意/拒绝")
                        .build(),
                NotificationTypeMetadata.builder()
                        .type("FRIEND_RESULT")
                        .actionable(false)
                        .actions(List.of())
                        .description("好友申请结果通知（同意/拒绝），无需操作")
                        .build(),
                NotificationTypeMetadata.builder()
                        .type("SYSTEM_ALERT")
                        .actionable(false)
                        .actions(List.of())
                        .description("系统类通知，仅提示")
                        .build()
        );
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}

