package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

@Configuration
class OAuth2ClientManagerConfig(
	private val clientRegistrationRepository: ClientRegistrationRepository,
	private val tokenExchangeService: TokenExchangeService
) {

	@Bean
	fun authorizedClientManager(): OAuth2AuthorizedClientManager {
		// Register support for client_credentials and our custom jwt-bearer flow
		val provider = OAuth2AuthorizedClientProviderBuilder.builder()
			.clientCredentials()
			.provider { context ->
				val grantType = context.clientRegistration.authorizationGrantType.value
				if (grantType == "urn:ietf:params:oauth:grant-type:jwt-bearer") {
					tokenExchangeService.performJwtBearerExchange(context)
				} else null
			}
			.build()

		val manager = AuthorizedClientServiceOAuth2AuthorizedClientManager(
			clientRegistrationRepository,
			InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository)
		)
		manager.setAuthorizedClientProvider(provider)
		return manager
	}
}
