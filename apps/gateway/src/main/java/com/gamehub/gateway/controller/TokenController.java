package com.gamehub.gateway.controller;

import com.gamehub.session.SessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Token 控制器 - 提供给前端获取当前登录用户的 access_token
 * 
 * 注意：Gateway 使用 WebFlux，OAuth2 登录后的 token 保存在服务器端 session 中。
 * 前端通过此端点获取 token，用于后续的 API 调用和 WebSocket 连接。
 * 
 * 重要：支持单点登录检查，确保返回的 token 与当前会话匹配。
 */
@Slf4j
@RestController
public class TokenController {

	private static final String SESSION_LOGIN_SESSION_ID_KEY = "LOGIN_SESSION_ID";
	
	private final ReactiveOAuth2AuthorizedClientManager authorizedClientManager;
	private final ReactiveJwtDecoder jwtDecoder;
	private final SessionRegistry sessionRegistry;

	public TokenController(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
	                       ReactiveJwtDecoder jwtDecoder,
	                       SessionRegistry sessionRegistry) {
		this.authorizedClientManager = authorizedClientManager;
		this.jwtDecoder = jwtDecoder;
		this.sessionRegistry = sessionRegistry;
	}

	/**
	 * 获取当前登录用户的 access_token（自动刷新策略）：
	 * - 若 access_token 已过期且存在 refresh_token，将静默刷新后返回最新 token
	 * - 若无法刷新（未登录/无 refresh_token/会话过期），返回 401
	 */
	@GetMapping("/token")
	public Mono<ResponseEntity<Map<String, Object>>> getToken(Authentication authentication, ServerWebExchange exchange) {
		if (authentication == null || !authentication.isAuthenticated()) {
			log.warn("Token获取失败：未认证");
			return Mono.just(ResponseEntity.status(401).body(
					Map.<String, Object>of("error", "未登录", "message", "请先通过 /oauth2/authorization/keycloak 登录")
			));
		}

		OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
				.withClientRegistrationId("keycloak")
				.principal(authentication)
				.build();

		return authorizedClientManager.authorize(authorizeRequest)
				.flatMap(authorizedClient -> {
					if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
						return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
								Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
						));
					}

					OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
					String tokenValue = accessToken.getTokenValue();
					
					return jwtDecoder.decode(tokenValue)
							.flatMap(jwt -> {
								String loginSessionId = extractLoginSessionId(jwt);
								String jwtJti = jwt.getId();
								String userId = jwt.getSubject();
								
								if (loginSessionId != null && !loginSessionId.isBlank()) {
									return exchange.getSession()
											.flatMap((WebSession session) -> {
												String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
												
												if (sessionLoginSessionId == null || sessionLoginSessionId.isBlank()) {
													log.debug("HTTP Session 中没有 loginSessionId，跳过验证（向后兼容）: userId={}", userId);
												} else if (!loginSessionId.equals(sessionLoginSessionId)) {
													log.error("Token 的 loginSessionId 与 HTTP Session 不匹配，拒绝返回: userId={}, loginSessionId={}, sessionLoginSessionId={}", 
															userId, loginSessionId, sessionLoginSessionId);
													return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
															Map.<String, Object>of("error", "Token 已失效", "message", "请重新登录")
													));
												}
												
												var sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
												if (sessionInfo != null) {
													if (sessionInfo.getStatus() != null 
															&& sessionInfo.getStatus() != com.gamehub.session.model.SessionStatus.ACTIVE) {
														log.error("会话状态非 ACTIVE，拒绝返回 token: userId={}, loginSessionId={}, status={}", 
																userId, loginSessionId, sessionInfo.getStatus());
														return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
																Map.<String, Object>of("error", "会话已失效", "message", "请重新登录")
														));
													}
													
													String sessionJti = sessionInfo.getSessionId();
													if (!jwtJti.equals(sessionJti)) {
														log.error("Token 的 jti 与会话的 sessionId 不匹配，拒绝返回: userId={}, jwtJti={}, sessionJti={}", 
																userId, jwtJti, sessionJti);
														return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
																Map.<String, Object>of("error", "Token 已失效", "message", "请重新登录")
														));
													}
												} else {
													log.debug("SessionRegistry 中找不到会话: userId={}, loginSessionId={}", userId, loginSessionId);
												}
												
												// 所有验证通过，返回 token
												Map<String, Object> result = new HashMap<>();
												result.put("access_token", tokenValue);
												result.put("token_type", accessToken.getTokenType().getValue());
												if (accessToken.getExpiresAt() != null) {
													result.put("expires_at", accessToken.getExpiresAt().toEpochMilli());
												}
												OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
												if (refreshToken != null) {
													result.put("refresh_token", refreshToken.getTokenValue());
													if (refreshToken.getExpiresAt() != null) {
														result.put("refresh_token_expires_at", refreshToken.getExpiresAt().toEpochMilli());
													}
												}
												return Mono.just(ResponseEntity.ok(result));
											});
								} else {
									log.debug("JWT 中没有 loginSessionId，跳过验证（向后兼容）: userId={}", userId);
									
									// 没有 loginSessionId，直接返回 token（向后兼容）
									Map<String, Object> result = new HashMap<>();
									result.put("access_token", tokenValue);
									result.put("token_type", accessToken.getTokenType().getValue());
									if (accessToken.getExpiresAt() != null) {
										result.put("expires_at", accessToken.getExpiresAt().toEpochMilli());
									}
									OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
									if (refreshToken != null) {
										result.put("refresh_token", refreshToken.getTokenValue());
										if (refreshToken.getExpiresAt() != null) {
											result.put("refresh_token_expires_at", refreshToken.getExpiresAt().toEpochMilli());
										}
									}
									return Mono.just(ResponseEntity.ok(result));
								}
							})
							.onErrorResume(ex -> {
								log.error("解析 JWT 失败", ex);
								// 如果解析失败，仍然返回 token（向后兼容）
								Map<String, Object> result = new HashMap<>();
								result.put("access_token", tokenValue);
								result.put("token_type", accessToken.getTokenType().getValue());
								if (accessToken.getExpiresAt() != null) {
									result.put("expires_at", accessToken.getExpiresAt().toEpochMilli());
								}
								OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
								if (refreshToken != null) {
									result.put("refresh_token", refreshToken.getTokenValue());
									if (refreshToken.getExpiresAt() != null) {
										result.put("refresh_token_expires_at", refreshToken.getExpiresAt().toEpochMilli());
									}
								}
								return Mono.just(ResponseEntity.ok(result));
							});
				})
				.defaultIfEmpty(ResponseEntity.status(401).body(
						Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
				));
	}

	/**
	 * 从 JWT 中提取 loginSessionId。
	 * 优先使用 sid，如果没有则尝试使用 session_state（向后兼容）。
	 */
	private String extractLoginSessionId(Jwt jwt) {
		// 优先使用 sid
		Object sidObj = jwt.getClaim("sid");
		if (sidObj != null) {
			String sid = sidObj.toString();
			if (sid != null && !sid.isBlank()) {
				return sid;
			}
		}
		
		// 如果没有 sid，尝试使用 session_state（向后兼容）
		Object sessionStateObj = jwt.getClaim("session_state");
		if (sessionStateObj != null) {
			String sessionState = sessionStateObj.toString();
			if (sessionState != null && !sessionState.isBlank()) {
				return sessionState;
			}
		}
		
		return null;
	}
}

