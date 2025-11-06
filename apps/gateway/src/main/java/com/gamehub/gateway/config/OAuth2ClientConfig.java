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
 * 配置 Reactive OAuth2 Client 管理器：
 * - 支持 authorization_code 与 refresh_token 流程
 * - 使得在 access_token 过期时可自动使用 refresh_token 静默刷新
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

		ReactiveOAuth2AuthorizedClientProvider provider =
				ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
						.authorizationCode()
						.refreshToken()
						.build();
		manager.setAuthorizedClientProvider(provider);
		return manager;
	}
}
