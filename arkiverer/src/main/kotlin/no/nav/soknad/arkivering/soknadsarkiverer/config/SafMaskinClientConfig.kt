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
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientRequest
import reactor.netty.http.client.HttpClientResponse
import java.net.http.HttpHeaders
import java.time.Duration
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
	private val maxBufferSize = 1024 * 1024 * 10 // 10MB

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
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationRepository: ClientRegistrationRepository
	): GraphQLWebClient {
		val clientRegistrationId = "saf-maskintilmaskin"

		// ExchangeFilterFunction that authorizes using client_credentials (machine-to-machine)
		val oauth2Filter = ExchangeFilterFunction.ofRequestProcessor { request ->
			val authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)
				.principal("m2m-service-account")
				.build()

			val authorizedClient = authorizedClientManager.authorize(authorizeRequest)
				?: throw IllegalStateException("Kunne ikke autorisere klienten '$clientRegistrationId'.")

			val newRequest = ClientRequest.from(request)
				.headers { it.setBearerAuth(authorizedClient.accessToken.tokenValue) }
				.build()

			reactor.core.publisher.Mono.just(newRequest)
		}
/*

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
*/

		val builder = webClientBuilder(authorizedClientManager, clientRegistrationRepository)
			.defaultRequest {
				it.header(NAV_CONSUMER_ID, applicationName)
			}
		return GraphQLWebClient(
			url = "${safUrl}${queryPath}",
			builder = builder
		)
	}

	private fun webClientBuilder(
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationRepository: ClientRegistrationRepository
	): WebClient.Builder {
		val oauth2Filter = oauth2ExchangeFilter(authorizedClientManager, clientRegistrationRepository, "saf-maskintilmaskin")

		val httpClient = HttpClient.create()
			.responseTimeout(Duration.ofSeconds(connectionTimeoutSeconds.toLong()))
		return WebClient.builder()
			.clientConnector(ReactorClientHttpConnector(httpClient))
			.codecs { configurer ->
				configurer.defaultCodecs().maxInMemorySize(maxBufferSize)
			}
			.filter(oauth2Filter)
	}


	private fun oauth2ExchangeFilter(
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationRepository: ClientRegistrationRepository,
		clientRegistrationId: String
	): ExchangeFilterFunction {
		return ExchangeFilterFunction { request, next ->
			val clientRegistration =
				clientRegistrationRepository.findByRegistrationId(clientRegistrationId)
					?: throw IllegalStateException("Fant ikke client registration for '$clientRegistrationId'")

			// Velg principal-verdien, men hold typen trygg
			val principalString: String?
			val principalAuth: Authentication?

			when {
				clientRegistration.authorizationGrantType == AuthorizationGrantType.CLIENT_CREDENTIALS -> {
					principalString = "system-service-account"
					principalAuth = null
				}

				clientRegistration.authorizationGrantType == AuthorizationGrantType("urn:ietf:params:oauth:grant-type:jwt-bearer") -> {
					principalAuth = SecurityContextHolder.getContext().authentication
						?: throw IllegalStateException("Ingen SecurityContext Authentication funnet for OBO-flyt.")
					principalString = null
				}

				else -> throw IllegalStateException("Grant type '${clientRegistration.authorizationGrantType.value}' støttes ikke.")
			}

			// Bygg authorize-request med riktig overload (typetrygg)
			val authorizeRequest = if (principalAuth != null) {
				// OBO: bruk Authentication og sett subject_token som attribute om nødvendig
				OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)
					.principal(principalAuth)
					.attributes { attrs ->
						// Legg på subject_token hvis din provider/authorizedClientProvider forventer det.
						val jwt = (principalAuth as? JwtAuthenticationToken)?.token?.tokenValue
						if (!jwt.isNullOrBlank()) {
							attrs["subject_token"] = jwt
						}
					}
					.build()
			} else {
				// client_credentials: bruk String principalName-overload
				OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)
					.principal(principalString!!)
					.build()
			}

			val authorizedClient = authorizedClientManager.authorize(authorizeRequest)
				?: throw IllegalStateException("Kunne ikke autorisere klienten '$clientRegistrationId'.")

			val mutatedRequest = ClientRequest.from(request)
				.headers { it.setBearerAuth(authorizedClient.accessToken.tokenValue) }
				.build()

			next.exchange(mutatedRequest)
		}
	}

}


