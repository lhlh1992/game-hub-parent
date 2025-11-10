package com.gamehub.systemservice.controller;

import com.gamehub.systemservice.common.Result;
import com.gamehub.systemservice.dto.request.LoginRequest;
import com.gamehub.systemservice.dto.response.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 认证控制器
 * 提供简单的 Token 获取接口（用于测试和开发）
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RestTemplate restTemplate;

    @Value("${keycloak.server-url:http://127.0.0.1:8180}")
    private String keycloakServerUrl;

    @Value("${keycloak.realm:my-realm}")
    private String realm;

    @Value("${keycloak.token-client-id:game-hub}")
    private String clientId;

    @Value("${keycloak.token-client-secret:}")
    private String clientSecret;

    /**
     * 获取 JWT Token（密码模式）
     * 
     * 注意：此接口仅用于开发和测试，生产环境建议使用授权码模式
     * 
     * @param request 登录请求（用户名和密码）
     * @return Token 信息
     */
    @PostMapping("/token")
    public Result<TokenResponse> getToken(@Valid @RequestBody LoginRequest request) {
        try {
            // 构建 Keycloak Token 端点 URL
            String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token",
                    keycloakServerUrl, realm);

            // 构建请求参数
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "password");
            params.add("client_id", clientId);
            if (clientSecret != null && !clientSecret.isEmpty()) {
                params.add("client_secret", clientSecret);
            }
            params.add("username", request.getUsername());
            params.add("password", request.getPassword());
            params.add("scope", "openid profile email roles");

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // 创建请求实体
            HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(params, headers);

            // 发送请求
            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, httpEntity, Map.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> body = response.getBody();
                    
                    // 构建响应
                    TokenResponse tokenResponse = TokenResponse.builder()
                            .accessToken((String) body.get("access_token"))
                            .refreshToken((String) body.get("refresh_token"))
                            .tokenType((String) body.get("token_type"))
                            .expiresIn(((Number) body.get("expires_in")).intValue())
                            .refreshExpiresIn(((Number) body.get("refresh_expires_in")).intValue())
                            .scope((String) body.get("scope"))
                            .build();

                    return Result.success("获取 Token 成功", tokenResponse);
                } else {
                    Map<String, Object> errorBody = response.getBody();
                    String errorMsg = "获取 Token 失败";
                    if (errorBody != null) {
                        String error = (String) errorBody.get("error");
                        String errorDescription = (String) errorBody.get("error_description");
                        errorMsg = errorDescription != null ? errorDescription : (error != null ? error : errorMsg);
                    }
                    log.error("Keycloak 返回错误: {}", errorBody);
                    return Result.error(401, errorMsg);
                }
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                // 处理 4xx 错误（如用户名密码错误、客户端配置错误等）
                String errorMsg = "获取 Token 失败";
                try {
                    String responseBody = e.getResponseBodyAsString();
                    if (responseBody != null && !responseBody.isEmpty()) {
                        // 简单解析 JSON 错误信息
                        if (responseBody.contains("error_description")) {
                            // 提取错误描述
                            int start = responseBody.indexOf("\"error_description\":\"") + 21;
                            int end = responseBody.indexOf("\"", start);
                            if (start > 20 && end > start) {
                                errorMsg = responseBody.substring(start, end);
                            }
                        } else if (responseBody.contains("error")) {
                            // 提取错误类型
                            int start = responseBody.indexOf("\"error\":\"") + 9;
                            int end = responseBody.indexOf("\"", start);
                            if (start > 8 && end > start) {
                                errorMsg = "错误: " + responseBody.substring(start, end);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    errorMsg = e.getStatusText() != null ? e.getStatusText() : e.getMessage();
                }
                log.error("Keycloak 返回 4xx 错误: {}", errorMsg);
                return Result.error(401, errorMsg);
            }

        } catch (RestClientException e) {
            log.error("调用 Keycloak Token 端点失败", e);
            return Result.error(500, "获取 Token 失败: " + e.getMessage());
        }
    }
}

