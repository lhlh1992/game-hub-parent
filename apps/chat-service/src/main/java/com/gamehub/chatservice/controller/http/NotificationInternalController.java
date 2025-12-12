package com.gamehub.chatservice.controller.http;

import com.gamehub.chatservice.service.NotificationPushService;
import com.gamehub.chatservice.service.dto.NotificationMessagePayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 内部通知推送接口。
 * 供其他服务（如 system-service）调用，通过 WS 推送给指定用户。
 *
 * 安全：接口需要网关/oauth2 鉴权，未做额外白名单。
 */
@RestController
@RequestMapping("/api/internal/notify")
@RequiredArgsConstructor
@Slf4j
public class NotificationInternalController {

    private final NotificationPushService notificationPushService;

    @PostMapping
    public ResponseEntity<Void> push(
            @Valid @RequestBody NotifyRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        // 记录调用来源，便于审计
        if (jwt != null) {
            log.debug("Internal notify call by subject={}, client={}",
                    jwt.getSubject(), jwt.getClaimAsString("client_id"));
        }

        NotificationMessagePayload payload = NotificationMessagePayload.builder()
                .type(request.getType())
                .title(request.getTitle())
                .content(request.getContent())
                .fromUserId(request.getFromUserId())
                .payload(request.getPayload())
                .actions(request.getActions())
                .build();

        notificationPushService.sendToUser(request.getUserId(), payload);
        return ResponseEntity.accepted().build();
    }

    /**
     * 内部推送请求体。
     */
    @Data
    public static class NotifyRequest {
        /**
         * 目标用户ID（Keycloak userId / sub）。
         */
        @NotBlank(message = "userId is required")
        private String userId;

        /**
         * 通知类型（INFO、FRIEND_REQUEST...）。
         */
        @NotBlank(message = "type is required")
        private String type;

        /**
         * 通知标题。
         */
        @NotBlank(message = "title is required")
        private String title;

        /**
         * 通知内容。
         */
        @NotBlank(message = "content is required")
        private String content;

        /**
         * 触发方用户（可选）。
         */
        private String fromUserId;

        /**
         * 额外透传数据（可选）。
         */
        private Object payload;

        /**
         * 可操作类型（可选），如 ["ACCEPT","REJECT"]。
         */
        private String[] actions;
    }
}

