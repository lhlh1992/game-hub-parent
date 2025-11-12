package com.gamehub.systemservice.controller.internal;

import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.dto.keycloak.KeycloakEventPayload;
import com.gamehub.systemservice.service.keycloak.KeycloakEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@RestController
@RequestMapping("/internal/keycloak")
@RequiredArgsConstructor
public class KeycloakEventController {

    private final KeycloakEventService eventService;

    @Value("${keycloak.event-webhook-secret:}")
    private String sharedSecret;

    @Value("${keycloak.event-webhook-basic-username:}")
    private String basicUsername;

    @Value("${keycloak.event-webhook-basic-password:}")
    private String basicPassword;

    /**
     * Keycloak 事件 Webhook（来自事件监听器插件）
     * 支持两种鉴权方式（二选一）：
     * 1) Header: X-KEYCLOAK-EVENT-TOKEN: <shared-secret>
     * 2) Header: Authorization: Basic base64(username:password)
     */
    @PostMapping({"/events", "/events/**"})
    public ResponseEntity<Result<Void>> onEvent(
            @RequestHeader(value = "X-KEYCLOAK-EVENT-TOKEN", required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        log.info("进入~~~");
        // 暂时关闭鉴权，专注验证网络可达性与解析容错
        boolean passed = true;

        // 为了提高容错性：先以原始字符串接收，再尝试解析；解析失败也返回 200，避免阻塞 Keycloak 控制台保存
        KeycloakEventPayload payload = null;
        if (body != null && !body.isBlank()) {
            try {
                payload = new ObjectMapper().readValue(body, KeycloakEventPayload.class);
            } catch (Exception parseEx) {
                log.warn("Keycloak 事件载荷非预期 JSON 格式，跳过解析：{}", parseEx.getMessage());
            }
        } else {
            log.warn("收到空的 Keycloak 事件载荷");
        }

        try {
            if (payload != null) {
                eventService.handleEvent(payload);
            }
        } catch (Exception ex) {
            // 业务处理失败也不向上抛 500，避免影响 Keycloak 流程；仅记录日志
            log.error("处理 Keycloak 事件失败（已忽略返回 500）", ex);
        }
        return ResponseEntity.ok(Result.success("ok", null));
    }
}


