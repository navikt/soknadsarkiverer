package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.expediagroup.graphql.client.spring.GraphQLWebClient
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import no.nav.soknad.arkivering.soknadsarkiverer.Constants.NAV_CONSUMER_ID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientRequest
import reactor.netty.http.client.HttpClientResponse
import java.util.concurrent.TimeUnit

@Configuration
class SafMaskinClientConfig(
	@param:Value("\${applicationName}") private val applicationName: String,
	@param:Value("\${saf.url}") private val safUrl: String,
	@param:Value("\${saf.path}") private val queryPath: String
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val connectionTimeoutSeconds = 10
	private val readTimeoutSeconds = 15
	private val writeTimeoutSeconds = 30

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("safWebClient")
	fun safTestWebClient(): GraphQLWebClient {
		return  GraphQLWebClient(
			url = "${safUrl}${queryPath}",
			builder = WebClient.builder()
				.defaultRequest {
					it.header(NAV_CONSUMER_ID, applicationName)
				}
		)
	}

	@Bean
	@Profile("prod | dev")
	@Qualifier("safWebClient")
	fun safWebClient(
		clientRegistrationRepository: ClientRegistrationRepository,
		authorizedClientService: OAuth2AuthorizedClientService
	): GraphQLWebClient {
		val clientRegistrationId = "saf-maskintilmaskin"
		val provider = OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build()
		val authorizedClientManager = AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService)
		authorizedClientManager.setAuthorizedClientProvider(provider)

		val oauth2Filter = ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager)
		oauth2Filter.setDefaultClientRegistrationId(clientRegistrationId)

		val builder = WebClient.builder()
			.clientConnector(
				ReactorClientHttpConnector(
				HttpClient.create()
					.keepAlive(false)
					.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (connectionTimeoutSeconds * 1000))
					.doOnConnected { conn ->
						conn.addHandlerLast(ReadTimeoutHandler(readTimeoutSeconds.toLong(), TimeUnit.SECONDS))
						conn.addHandlerLast(WriteTimeoutHandler(writeTimeoutSeconds.toLong(), TimeUnit.SECONDS))
					}
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
			.filter(oauth2Filter)
			.defaultRequest {
				it.header(NAV_CONSUMER_ID, applicationName)
			}

		return GraphQLWebClient(
			url = "${safUrl}${queryPath}",
			builder = builder
		)
	}

}


