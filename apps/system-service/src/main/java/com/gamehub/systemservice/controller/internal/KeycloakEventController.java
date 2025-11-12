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

    @Value("${keycloak.event-webhook-basic-username:}")
    private String basicUsername;

    @Value("${keycloak.event-webhook-basic-password:}")
    private String basicPassword;

    /**
     * Keycloak 事件 Webhook（来自 vymalo/keycloak-webhook 插件）
     * 使用 Basic Auth 鉴权：Header: Authorization: Basic base64(username:password)
     */
    @PostMapping({"/events", "/events/**"})
    public ResponseEntity<Result<Void>> onEvent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) String body) {
        
        // 鉴权校验（vymalo/keycloak-webhook 插件使用 Basic Auth）
        boolean passed = false;
        
        // 检查 Basic Auth
        if (authorization != null && authorization.toLowerCase().startsWith("basic ")) {
            if (basicUsername != null && !basicUsername.isBlank() && 
                basicPassword != null && !basicPassword.isBlank()) {
                try {
                    String base64Credentials = authorization.substring(6).trim();
                    String credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
                    String[] parts = credentials.split(":", 2);
                    if (parts.length == 2 && parts[0].equals(basicUsername) && parts[1].equals(basicPassword)) {
                        passed = true;
                        log.debug("通过 Basic Auth 鉴权");
                    } else {
                        log.warn("Basic Auth 用户名或密码不匹配");
                    }
                } catch (Exception ex) {
                    log.warn("Basic Auth 解析失败：{}", ex.getMessage());
                }
            } else {
                log.warn("Basic Auth 配置未设置，无法验证");
            }
        }
        
        if (!passed) {
            log.warn("Keycloak 事件回调鉴权失败：hasAuth={}, basicUsername配置={}", 
                    authorization != null,
                    basicUsername != null && !basicUsername.isBlank());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error("Unauthorized"));
        }

        // 解析 JSON（vymalo/keycloak-webhook 插件使用直接格式，不是包装格式）
        // 格式: {"type": "REGISTER", "userId": "...", "details": {"email": "...", "username": "...", ...}}
        KeycloakEventPayload payload = null;
        if (body != null && !body.isBlank()) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                // 先尝试直接解析为 Event（vymalo/keycloak-webhook 插件格式）
                KeycloakEventPayload.Event directEvent = mapper.readValue(body, KeycloakEventPayload.Event.class);
                if (directEvent != null && directEvent.getType() != null) {
                    payload = new KeycloakEventPayload();
                    payload.setEvent(directEvent);
                    log.info("接收 Keycloak 事件：type={}, realmId={}, userId={}", 
                            directEvent.getType(), directEvent.getRealmId(), directEvent.getUserId());
                }
            } catch (Exception e1) {
                // 直接格式解析失败，尝试包装格式（兼容其他插件）
                try {
                    payload = mapper.readValue(body, KeycloakEventPayload.class);
                    if (payload != null && (payload.getEvent() != null || payload.getAdminEvent() != null)) {
                        String eventType = payload.getEvent() != null ? payload.getEvent().getType() : 
                                (payload.getAdminEvent() != null ? payload.getAdminEvent().getOperationType() : "unknown");
                        String realmId = payload.getEvent() != null ? payload.getEvent().getRealmId() : 
                                (payload.getAdminEvent() != null ? payload.getAdminEvent().getRealmId() : "unknown");
                        String userId = payload.getEvent() != null ? payload.getEvent().getUserId() : null;
                        log.info("接收 Keycloak 事件（包装格式）：type={}, realmId={}, userId={}", eventType, realmId, userId);
                    }
                } catch (Exception e2) {
                    log.warn("JSON 解析失败：{}，原始内容：{}", 
                            e2.getMessage(), body.length() > 200 ? body.substring(0, 200) + "..." : body);
                }
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


