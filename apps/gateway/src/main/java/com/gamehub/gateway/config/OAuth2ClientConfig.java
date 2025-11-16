package com.gamehub.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;

/**
 * OAuth2 Client 配置：创建 OAuth2 授权客户端管理器
 * 
 * 作用：
 * 1. 创建 Manager：用于获取和管理用户的 OAuth2 授权信息（包含 access_token、refresh_token）
 * 2. 配置授权流程：告诉 Manager 支持哪些授权方式
 * 
 * 使用场景：
 * - TokenController：前端调用 /token 时，通过 Manager 获取 token（如果过期会自动刷新）
 * - LoginSessionKickHandler：登录成功后，通过 Manager 获取 token 进行会话管理
 * 
 * 配置内容：
 * - clientRegistrationRepository：客户端注册信息（从配置文件读取 Keycloak 地址等）
 * - authorizedClientService：存储已授权的客户端（按 userId 存储，会覆盖）
 * - provider：授权流程提供者（支持授权码和刷新 token）
 */
@Configuration
public class OAuth2ClientConfig {

	/**
	 * 创建 OAuth2 授权客户端管理器
	 * 
	 * @param clientRegistrationRepository 客户端注册信息仓库（从配置读取 Keycloak 地址、client-id 等）
	 * @param authorizedClientService 授权客户端服务（存储已授权的客户端，按 userId 存储）
	 * @return Manager：提供获取 token 的能力，支持自动刷新
	 */
	@Bean
	public ReactiveOAuth2AuthorizedClientManager reactiveAuthorizedClientManager(
			ReactiveClientRegistrationRepository clientRegistrationRepository,
			ReactiveOAuth2AuthorizedClientService authorizedClientService) {
		// 创建 Manager：负责获取和管理 OAuth2 授权客户端
		AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
				new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
						clientRegistrationRepository, authorizedClientService);

		// 配置授权流程提供者：定义 Manager 支持哪些授权方式
		ReactiveOAuth2AuthorizedClientProvider provider =
				ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
						.authorizationCode()  // 首次登录：用户通过浏览器登录，获取授权码，换取 token
						.refreshToken()       // 自动刷新：token 过期时，使用 refresh_token 自动刷新
						.build();
		
		// 设置提供者：Manager 会根据 token 状态自动选择流程
		// - 如果 token 不存在或已过期 → 使用 authorizationCode（需要用户登录）
		// - 如果 token 过期但 refresh_token 有效 → 使用 refreshToken（静默刷新）
		manager.setAuthorizedClientProvider(provider);
		return manager;
	}
}
