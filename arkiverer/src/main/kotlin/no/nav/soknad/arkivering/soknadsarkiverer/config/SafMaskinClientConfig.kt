package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.expediagroup.graphql.client.spring.GraphQLWebClient
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
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
import java.time.Duration

@Configuration
class SafMaskinClientConfig(
	@param:Value("\${applicationName}") private val applicationName: String,
	@param:Value("\${saf.url}") private val safUrl: String,
	@param:Value("\${saf.path}") private val queryPath: String
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val bufferSizeMb: Int = 10

	private val responseTimeout = Duration.ofSeconds(15)
	private val maxBufferSize = 1024 * 1024 * bufferSizeMb

	@Bean
	@Profile("!prod & !dev")
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
	@Profile("prod", "dev")
	@Qualifier("safWebClient")
	fun safGraphQlWebClient(@Qualifier("safHttpClient") safWebClient: WebClient): GraphQLWebClient {
		return GraphQLWebClient(
			url = "${safUrl}${queryPath}",
			builder = safWebClient.mutate()
				.defaultRequest {
					it.header(NAV_CONSUMER_ID, applicationName)
				})
	}

	@Bean
	@Profile("prod", "dev")
	@Qualifier("safHttpClient")
	fun safWebClient(
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationRepository: ClientRegistrationRepository
	): WebClient {

		// ENDRET: Byttet fra "saf-obo" til "saf-maskintilmaskin" for å bruke maskin-token
		val oauth2Filter = oauth2ExchangeFilter(authorizedClientManager, clientRegistrationRepository, "saf-maskintilmaskin")

		val httpClient = HttpClient.create()
			.responseTimeout(responseTimeout)

		return WebClient.builder()
			.clientConnector(ReactorClientHttpConnector(httpClient))
			.codecs { configurer ->
				configurer.defaultCodecs().maxInMemorySize(maxBufferSize)
			}
			.filter(oauth2Filter)
			.build()
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

			val principalString: String?
			val principalAuth: Authentication?

			when {
				clientRegistration.authorizationGrantType == AuthorizationGrantType.CLIENT_CREDENTIALS -> {
					// Siden vi nå bruker saf-cc, vil koden treffe her.
					// Det kreves ingen innlogget bruker i SecurityContextHolder.
					principalString = "system-service-account"
					principalAuth = null
				}

				clientRegistration.authorizationGrantType == AuthorizationGrantType("urn:ietf:params:oauth:grant-type:jwt-bearer") -> {
					principalAuth = SecurityContextHolder.getContext().authentication
						?: throw IllegalStateException("Ingen SecurityContext Authentication funnet.")
					principalString = null
				}

				else -> throw IllegalStateException("Grant type '${clientRegistration.authorizationGrantType.value}' støttes ikke.")
			}

			val authorizeRequest = if (principalAuth != null) {
				OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)
					.principal(principalAuth)
					.attributes { attrs ->
						val jwt = (principalAuth as? JwtAuthenticationToken)?.token?.tokenValue
						if (!jwt.isNullOrBlank()) {
							attrs["subject_token"] = jwt
						}
					}
					.build()
			} else {
				// Bygger forespørsel basert på den statiske system-strengen
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


