package no.nav.soknad.arkivering.soknadsarkiverer.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.Connection
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.TcpClient

@EnableConfigurationProperties(ClientConfigurationProperties::class)
@Configuration
class WebClientConfig(private val maxMessageSize: Int = 1024 * 1024 * 300) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("archiveWebClient")
	fun archiveTestWebClient(): WebClient = WebClient.builder().build()


	@Bean
	@Profile("prod | dev")
	@Qualifier("archiveWebClient")
	fun archiveWebClient(
		oAuth2AccessTokenService: OAuth2AccessTokenService,
		clientConfigurationProperties: ClientConfigurationProperties
	): WebClient {

		logger.info("Initializing archiveWebClient")
		val properties: ClientProperties = clientConfigurationProperties.registration
			?.get("arkiv")
			?: throw RuntimeException("Could not find oauth2 client config for archiveWebClient")

		logClientProperties(properties)
		val tcpClient = TcpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
			.doOnConnected { connection: Connection ->
				connection.addHandlerLast(ReadTimeoutHandler(60))
					.addHandlerLast(WriteTimeoutHandler(60))
			}
		val exchangeStrategies = ExchangeStrategies.builder()
			.codecs { configurer: ClientCodecConfigurer ->
				configurer
					.defaultCodecs()
					.maxInMemorySize(maxMessageSize)
			}
			.build()

		return WebClient.builder()
			.filter(bearerTokenFilter(properties, oAuth2AccessTokenService))
			.exchangeStrategies(exchangeStrategies)
			.clientConnector(ReactorClientHttpConnector(HttpClient.from(tcpClient)))
			.build()
	}

	private fun bearerTokenFilter(
		clientProperties: ClientProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService
	) =
		{ request: ClientRequest, next: ExchangeFunction ->
			val response: OAuth2AccessTokenResponse = oAuth2AccessTokenService.getAccessToken(clientProperties)

			val filtered = ClientRequest.from(request)
				.headers { it.setBearerAuth(response.accessToken) }
				.build()
			next.exchange(filtered)
		}

	private fun logClientProperties(properties: ClientProperties) {
		logger.info("Properties.tokenEndpointUrl = '${properties.tokenEndpointUrl}'")
		logger.info("Properties.grantType = '${properties.grantType}'")
		logger.info("Properties.scope = '${properties.scope}'")
		logger.info("Properties.resourceUrl = '${properties.resourceUrl}'")
		val clientSecret = when {
			(properties.authentication?.clientSecret == null || properties.authentication?.clientSecret == "") -> "MISSING"
			else -> properties.authentication.clientSecret.substring(0, 2)
		}
		logger.info("Properties.authentication.clientSecret = '$clientSecret'")
		logger.info("Properties.authentication.clientAuthMethod = '${properties.authentication?.clientAuthMethod}'")
	}
}
