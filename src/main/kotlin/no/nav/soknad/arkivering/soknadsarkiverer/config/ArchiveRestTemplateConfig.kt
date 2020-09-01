package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.*
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestTemplate
import java.util.*


@Profile("prod | dev")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@Configuration
class ArchiveRestTemplateConfig(private val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	@Profile("prod | dev")
	@Qualifier("archiveRestTemplate")
	@Scope("prototype")
fun archiveRestTemplate(restTemplateBuilder: RestTemplateBuilder,
												oAuth2AccessTokenService: OAuth2AccessTokenService,
												clientConfigurationProperties: ClientConfigurationProperties): RestTemplate? {
		val properties: ClientProperties? = clientConfigurationProperties.registration?.get("soknadsarkiverer")
		logger.info("Properties.tokenEndpointUrl= ${properties?.tokenEndpointUrl}")
		logger.info("Properties.scope= ${properties?.scope}")
		logger.info("Properties.authentication.clientId= ${properties?.authentication?.clientId}")

		val clientProperties: ClientProperties = Optional.ofNullable(properties)
			.orElseThrow({ RuntimeException("could not find oauth2 client config for archiveRestTemplate") })
		// Set correlation_id_header??
		return restTemplateBuilder
			.additionalInterceptors(bearerTokenInterceptor(clientProperties, oAuth2AccessTokenService))
			.build()
	}

	private fun bearerTokenInterceptor(clientProperties: ClientProperties,
																		 oAuth2AccessTokenService: OAuth2AccessTokenService): ClientHttpRequestInterceptor? {
		return ClientHttpRequestInterceptor { request: HttpRequest, body: ByteArray?, execution: ClientHttpRequestExecution ->
			val response: OAuth2AccessTokenResponse = oAuth2AccessTokenService.getAccessToken(clientProperties)
			request.headers.setBearerAuth(response.getAccessToken())
			execution.execute(request, body!!)
		}
	}

}
