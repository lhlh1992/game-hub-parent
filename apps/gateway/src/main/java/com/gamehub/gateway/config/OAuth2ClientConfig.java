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
 * OAuth2 Client 配置：支持自动刷新 token
 * - authorization_code：首次登录流程
 * - refresh_token：token 过期时自动刷新
 */
@Configuration
public class OAuth2ClientConfig {

	@Bean
	public ReactiveOAuth2AuthorizedClientManager reactiveAuthorizedClientManager(
			ReactiveClientRegistrationRepository clientRegistrationRepository,
			ReactiveOAuth2AuthorizedClientService authorizedClientService) {
		AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
				new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
						clientRegistrationRepository, authorizedClientService);

		// 配置 Provider：支持授权码流程和刷新 token 流程
		ReactiveOAuth2AuthorizedClientProvider provider =
				ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
						.authorizationCode()  // 首次登录
						.refreshToken()       // 自动刷新
						.build();
		manager.setAuthorizedClientProvider(provider);
		return manager;
	}
}
