package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.Constants.BEARER
import no.nav.soknad.arkivering.soknadsarkiverer.Constants.NAV_CONSUMER_ID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientRequest
import reactor.netty.http.client.HttpClientResponse
import java.util.*

@Configuration
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class SafMaskinClientConfig(
	@Value("\${applicationName}") private val applicationName: String,
	@Value("\${saf.url}") private val safUrl: String,
	@Value("\${saf.path}") private val queryPath: String
) {
	private val logger = LoggerFactory.getLogger(javaClass)


	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("safWebClientBuilder")
	fun safTestWebClientBuilder(): WebClient.Builder =
		WebClient.builder()
				.defaultRequest {
					it.header(NAV_CONSUMER_ID, applicationName)
				}

	@Bean
	@Profile("prod | dev")
	@Qualifier("safWebClientBuilder")
	fun safWebClientBuilder(
		oauth2Config: ClientConfigurationProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService
	) = WebClient.builder()
			.clientConnector(
				ReactorClientHttpConnector(
					HttpClient.create()
						.doOnRequest { request: HttpClientRequest, _ ->
							logger.info("{} {} {}", request.version(), request.method(), request.resourceUrl())
						}
						.doOnResponse { response: HttpClientResponse, _ ->
							logger.info(
								"{} - {} {} {}",
								response.status().toString(),
								response.version(),
								response.method(),
								response.resourceUrl()
							)
						}
				)
			)
			.defaultRequest {
				it.header(NAV_CONSUMER_ID, applicationName)
				it.header(
					HttpHeaders.AUTHORIZATION,
					"$BEARER${oAuth2AccessTokenService.getAccessToken(getClientProperties(oauth2Config)).access_token}",
				)
			}


	private val safMaskintilmaskin = "saf-maskintilmaskin"

	fun getClientProperties(oauth2Config: ClientConfigurationProperties) = oauth2Config.registration[safMaskintilmaskin]
		?: throw RuntimeException("could not find oauth2 client config for $safMaskintilmaskin")
}

