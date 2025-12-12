package com.gamehub.chatservice.controller.http;

import com.gamehub.chatservice.controller.http.dto.NotifyRequest;
import com.gamehub.chatservice.service.NotificationPushService;
import com.gamehub.chatservice.service.dto.NotificationMessagePayload;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

}


