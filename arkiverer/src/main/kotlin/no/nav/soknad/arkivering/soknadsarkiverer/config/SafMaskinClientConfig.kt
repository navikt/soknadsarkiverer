package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.expediagroup.graphql.client.spring.GraphQLWebClient
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
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
	@Qualifier("safWebClient")
	fun safTestWebClient(): GraphQLWebClient =
		GraphQLWebClient(url = "${safUrl}${queryPath}", builder = WebClient.builder())

	@Bean
	@Profile("prod | dev")
	@Qualifier("safWebClient")
	fun safGraphQLWebClient(
		oauth2Config: ClientConfigurationProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService
		) = GraphQLWebClient(
		url = "${safUrl}${queryPath}",
		builder = WebClient.builder()
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
				it.header(HEADER_CALL_ID, UUID.randomUUID().toString())
				it.header(CORRELATION_ID, UUID.randomUUID().toString())
				it.header(NAV_CONSUMER_ID, applicationName)
				it.header(
					HttpHeaders.AUTHORIZATION,
					"Bearer ${oAuth2AccessTokenService.getAccessToken(getClientProperties(oauth2Config)).accessToken}",
				)
			}
	)

	private val safMaskintilmaskin = "saf-maskintilmaskin"

	fun getClientProperties(oauth2Config: ClientConfigurationProperties) = oauth2Config.registration[safMaskintilmaskin]
		?: throw RuntimeException("could not find oauth2 client config for $safMaskintilmaskin")

	companion object {
		const val NAV_CONSUMER_ID = "Nav-Consumer-Id"
		const val HEADER_CALL_ID = "Nav-Call-Id"
		const val CORRELATION_ID = "correlation_id"
	}
}

