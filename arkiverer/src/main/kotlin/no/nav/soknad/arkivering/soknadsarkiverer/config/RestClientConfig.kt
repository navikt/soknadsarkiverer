package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.ArchivingTimeoutProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileFetchTimeoutProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.InnsendingApiProperties
import no.nav.soknad.innsending.api.HentInnsendteFilerApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class RestClientConfig {

	private val logger = LoggerFactory.getLogger(javaClass)


	@Bean
	@Profile("prod | dev")
	@Qualifier("archiveRestClient")
	fun archiveWebClient(
		@Value("\${joark.host}") joarkHost: String,
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationRepository: ClientRegistrationRepository,
		archivingTimeoutProperties: ArchivingTimeoutProperties
	): RestClient {

		val oauth2Interceptor = createOauth2Interceptor(authorizedClientManager, "arkiv", clientRegistrationRepository)
		return RestClient.builder()
			.baseUrl(joarkHost)
			.requestFactory(
				timeouts(archivingTimeoutProperties.readTimeout,archivingTimeoutProperties.connectTimeout, archivingTimeoutProperties.exchangeTimeout))
			.requestInterceptor(oauth2Interceptor)
			.build()

	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("archiveRestClient")
	fun archiveTestWebClient(
		@Value("\${joark.host}") joarkHost: String, archivingTimeoutProperties: ArchivingTimeoutProperties
	): RestClient = RestClient.builder().baseUrl(joarkHost).requestFactory(
			timeouts(
				readTimeoutMinutes = archivingTimeoutProperties.readTimeout,
				connectTimeoutSeconds = archivingTimeoutProperties.connectTimeout,
				exchangeTimeoutMinutes = archivingTimeoutProperties.exchangeTimeout
			)
		).build()


	private fun timeouts(readTimeoutMinutes: Long, connectTimeoutSeconds: Long, exchangeTimeoutMinutes: Long? = null): ClientHttpRequestFactory {
		val factory = SimpleClientHttpRequestFactory()
		factory.setReadTimeout(Duration.ofMinutes(readTimeoutMinutes))
		factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
		//factory.setExchangeTimeout(Duration.ofMinutes(exchangeTimeoutMinutes ?: readTimeoutMinutes))
		return factory
	}


	@Bean
	@Profile("prod | dev")
	@Qualifier("innsendingApiRestClient")
	fun innsendingApiClient(
		innsendingApiProperties: InnsendingApiProperties,
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationRepository: ClientRegistrationRepository,
		fileFetchTimeoutProperties: FileFetchTimeoutProperties
	): RestClient {

		val oauth2Interceptor = createOauth2Interceptor(authorizedClientManager, "innsendingApi", clientRegistrationRepository)
		return RestClient.builder()
			.baseUrl(innsendingApiProperties.host)
			.requestFactory(
				timeouts(fileFetchTimeoutProperties.readTimeout.toLong(),fileFetchTimeoutProperties.connectTimeout.toLong()))
			.requestInterceptor(oauth2Interceptor)
			.build()

	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("innsendingApiRestClient")
	fun innsendingApiClientWithoutOAuth(
		innsendingApiProperties: InnsendingApiProperties, fileFetchTimeoutProperties: FileFetchTimeoutProperties
	) = RestClient.builder().baseUrl(innsendingApiProperties.host).requestFactory(
		timeouts(
			readTimeoutMinutes = fileFetchTimeoutProperties.readTimeout.toLong(),
			connectTimeoutSeconds = fileFetchTimeoutProperties.connectTimeout.toLong()
		)
	).build()

	@Bean
	fun hentInnsendteFilerApi(
		innsendingApiProperties: InnsendingApiProperties,
		@Qualifier("innsendingApiRestClient") innsendingApiClient: RestClient): HentInnsendteFilerApi {
		//Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return HentInnsendteFilerApi(innsendingApiClient)
	}

	@Bean
	fun innsenderHealthApi(innsendingApiProperties: InnsendingApiProperties) = no.nav.soknad.innsending.api.HealthApi(innsendingApiProperties.host)



	/**
	 * Privat hjelpemetode for å lage en gjenbrukbar interceptor.
	 * Denne metoden fungerer for både 'jwt-bearer' (som krever en bruker-principal)
	 * og 'client_credentials' (som ikke krever det).
	 */
	private fun createOauth2Interceptor(
		authorizedClientManager: OAuth2AuthorizedClientManager,
		clientRegistrationId: String,
		clientRegistrationRepository: ClientRegistrationRepository
	): ClientHttpRequestInterceptor {
		return ClientHttpRequestInterceptor { request, body, execution ->
			logger.info("createOauth2Interceptor for clientRegistrationId: $clientRegistrationId")
			val clientRegistration = clientRegistrationRepository.findByRegistrationId(clientRegistrationId)
				?: throw IllegalStateException("Fant ikke klient-registrering for '$clientRegistrationId'.")

			val authorizeRequestBuilder = OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)

			if (clientRegistration.authorizationGrantType == AuthorizationGrantType.CLIENT_CREDENTIALS) {
				// ✅ For machine-to-machine flow, just use a static principal name
				authorizeRequestBuilder.principal("m2m-service-account")
			} else {
				// ✅ For OBO (JWT-bearer), forward the current authenticated user
				val principal = SecurityContextHolder.getContext().authentication
					?: throw IllegalStateException("Ingen SecurityContext Authentication funnet for OBO flyt.")
				authorizeRequestBuilder.principal(principal)
			}

			val authorizeRequest = authorizeRequestBuilder.build()

			val authorizedClient = authorizedClientManager.authorize(authorizeRequest)
				?: throw IllegalStateException(
					"Kunne ikke autorisere klienten '$clientRegistrationId'. " +
						"Sjekk konfigurasjon og grant-type."
				)

			request.headers.setBearerAuth(authorizedClient.accessToken.tokenValue)
			execution.execute(request, body)
		}
	}

}
