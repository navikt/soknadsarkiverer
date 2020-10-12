package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient

@EnableConfigurationProperties(ClientConfigurationProperties::class)
@Configuration
class WebClientConfig(private val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	@Qualifier("basicWebClient")
	fun createWebClient() = WebClient.builder().build()

	@Bean
	@Profile("spring | test | docker | default")
	@Qualifier("archiveWebClient")
	@Scope("prototype")
	@Lazy
	fun archiveTestWebClient() = createWebClient()


	@Bean
	@Profile("prod | dev")
	@Qualifier("archiveWebClient")
	@Scope("prototype")
	fun archiveWebClient(oAuth2AccessTokenService: OAuth2AccessTokenService,
											 clientConfigurationProperties: ClientConfigurationProperties): WebClient {

		val properties: ClientProperties = clientConfigurationProperties.registration
			?. get("soknadsarkiverer")
			?: throw RuntimeException("Could not find oauth2 client config for archiveWebClient")

		logClientProperties(properties)

		return WebClient.builder()
			.filter(bearerTokenFilter(properties, oAuth2AccessTokenService))
			.build()
	}

	private fun bearerTokenFilter(clientProperties: ClientProperties, oAuth2AccessTokenService: OAuth2AccessTokenService) =
		ExchangeFilterFunction { request: ClientRequest, exchangeFunction: ExchangeFunction ->
			val response: OAuth2AccessTokenResponse = oAuth2AccessTokenService.getAccessToken(clientProperties)
			request.headers().setBearerAuth(response.accessToken)
			exchangeFunction.exchange(request)
		}

	private fun logClientProperties(properties: ClientProperties) {
		logger.info("Properties.tokenEndpointUrl= ${properties.tokenEndpointUrl}")
		logger.info("Properties.grantType= ${properties.grantType}")
		logger.info("Properties.scope= ${properties.scope}")
		logger.info("Properties.resourceUrl= ${properties.resourceUrl}")
		logger.info("Properties.authentication.clientId= ${properties.authentication?.clientId}")
		val clientSecret = when {
			(properties.authentication?.clientSecret == null || properties.authentication?.clientSecret == "") -> "MISSING"
			(properties.authentication.clientSecret == appConfiguration.kafkaConfig.password) -> "xxxx"
			else -> properties.authentication.clientSecret.substring(0,2)
		}
		logger.info("Properties.authentication.clientSecret= $clientSecret")
		logger.info("Properties.authentication.clientAuthMethod= ${properties.authentication?.clientAuthMethod}")
	}
}
