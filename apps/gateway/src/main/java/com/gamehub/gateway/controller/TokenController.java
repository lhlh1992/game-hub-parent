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
		log.info("【Token获取】🚀 ========== /token 接口被调用 ========== authentication={}", 
				authentication != null ? authentication.getName() : "null");
		
		if (authentication == null || !authentication.isAuthenticated()) {
			log.warn("【Token获取】❌ 未认证，返回 401");
			return Mono.just(ResponseEntity.status(401).body(
					Map.<String, Object>of("error", "未登录", "message", "请先通过 /oauth2/authorization/keycloak 登录")
			));
		}

		OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
				.withClientRegistrationId("keycloak")
				.principal(authentication)
				.build();
		
		log.info("【Token获取】📞 调用 authorizedClientManager.authorize() 获取 token");

		return authorizedClientManager.authorize(authorizeRequest)
				.flatMap(authorizedClient -> {
					if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
						return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
								Map.<String, Object>of("error", "未找到授权客户端", "message", "请先完成 OAuth2 登录")
						));
					}

					OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
					String tokenValue = accessToken.getTokenValue();

					// ========== 步骤4修复：检查 token 的 loginSessionId 是否与当前会话匹配 ==========
					// 如果 token 的 loginSessionId 与 SessionRegistry 中的不匹配，说明 token 已被其他登录覆盖
					log.info("【Token获取】🔍 开始验证 token: token前10位={}", 
							tokenValue != null && tokenValue.length() > 10 ? tokenValue.substring(0, 10) : tokenValue);
					
					return jwtDecoder.decode(tokenValue)
							.flatMap(jwt -> {
								// 从 JWT 中提取 loginSessionId
								String loginSessionId = extractLoginSessionId(jwt);
								String jwtJti = jwt.getId();
								String userId = jwt.getSubject();
								
								log.info("【Token获取】📋 JWT 信息提取: userId={}, jti={}, loginSessionId={}", 
										userId, jwtJti, loginSessionId);
								
								// 如果 JWT 中有 loginSessionId，检查会话状态
								if (loginSessionId != null && !loginSessionId.isBlank()) {
									// ========== 关键检查：验证 token 的 loginSessionId 是否与当前 HTTP Session 匹配 ==========
									return exchange.getSession()
											.flatMap((WebSession session) -> {
												String sessionLoginSessionId = (String) session.getAttributes().get(SESSION_LOGIN_SESSION_ID_KEY);
												
												log.info("【Token获取】🔐 Session 验证: tokenLoginSessionId={}, sessionLoginSessionId={}", 
														loginSessionId, sessionLoginSessionId);
												
												// 如果 Session 中没有 loginSessionId，说明是旧登录（向后兼容），跳过此检查
												if (sessionLoginSessionId == null || sessionLoginSessionId.isBlank()) {
													log.warn("【Token获取】⚠️ HTTP Session 中没有 loginSessionId，跳过 Session 验证（向后兼容）: userId={}, jwtJti={}", 
															userId, jwtJti);
												} else if (!loginSessionId.equals(sessionLoginSessionId)) {
													// Token 的 loginSessionId 与 Session 中的不匹配，说明 token 已被其他登录覆盖
													log.error("【Token获取】❌ Token 的 loginSessionId 与 HTTP Session 不匹配，token 已被覆盖！拒绝返回 token: " +
															"tokenLoginSessionId={}, sessionLoginSessionId={}, userId={}, jwtJti={}", 
															loginSessionId, sessionLoginSessionId, userId, jwtJti);
													return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
															Map.<String, Object>of("error", "Token 已失效", "message", "请重新登录")
													));
												}
												
												// Session 验证通过，继续检查 SessionRegistry
												var sessionInfo = sessionRegistry.getLoginSessionByLoginSessionId(loginSessionId);
												if (sessionInfo != null) {
													log.info("【Token获取】📊 查询到会话信息: loginSessionId={}, sessionId={}, status={}", 
															loginSessionId, sessionInfo.getSessionId(), sessionInfo.getStatus());
													
													// 检查会话状态
													if (sessionInfo.getStatus() != null 
															&& sessionInfo.getStatus() != com.gamehub.session.model.SessionStatus.ACTIVE) {
														log.error("【Token获取】❌ 会话状态非 ACTIVE，拒绝返回 token: loginSessionId={}, status={}, userId={}, jwtJti={}", 
																loginSessionId, sessionInfo.getStatus(), userId, jwtJti);
														return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
																Map.<String, Object>of("error", "会话已失效", "message", "请重新登录")
														));
													}
													
													// 检查 token 的 jti 是否与会话的 sessionId 匹配
													String sessionJti = sessionInfo.getSessionId();
													if (!jwtJti.equals(sessionJti)) {
														log.error("【Token获取】❌ Token 的 jti 与会话的 sessionId 不匹配，token 已被覆盖！拒绝返回 token: " +
																"jwtJti={}, sessionJti={}, loginSessionId={}, userId={}", 
																jwtJti, sessionJti, loginSessionId, userId);
														return Mono.just(ResponseEntity.status(401).<Map<String, Object>>body(
																Map.<String, Object>of("error", "Token 已失效", "message", "请重新登录")
														));
													}
													
													log.info("【Token获取】✅ Token 验证通过: loginSessionId={}, jti={}, status={}", 
															loginSessionId, jwtJti, sessionInfo.getStatus());
												} else {
													log.warn("【Token获取】⚠️ SessionRegistry 中找不到会话: loginSessionId={}, userId={}, jwtJti={}", 
															loginSessionId, userId, jwtJti);
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
									log.warn("【Token获取】⚠️ JWT 中没有 loginSessionId，跳过验证（向后兼容）: userId={}, jwtJti={}", 
											userId, jwtJti);
									
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
								log.error("【Token获取】解析 JWT 失败", ex);
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

