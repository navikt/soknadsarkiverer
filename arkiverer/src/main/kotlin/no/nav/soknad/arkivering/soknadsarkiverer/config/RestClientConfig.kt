package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.Constants
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.ArchivingTimeoutProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileFetchTimeoutProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.InnsendingApiProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport.TokenService
import no.nav.soknad.innsending.api.HentInnsendteFilerApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpRequest
import org.springframework.http.client.*
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class RestClientConfig {

	@Bean
	@Profile("prod | dev")
	@Qualifier("archiveRestClient")
	fun archiveWebClient(
		@Value("\${joark.host}") joarkHost: String,
		oAuth2AccessTokenService: OAuth2AccessTokenService,
		clientConfigurationProperties: ClientConfigurationProperties,
		archivingTimeoutProperties: ArchivingTimeoutProperties
	): RestClient {

		return restClientOAuth2Client(
			baseUrl = joarkHost,
			timeouts = timeouts(
				readTimeoutMinutes = archivingTimeoutProperties.readTimeout,
				connectTimeoutSeconds = archivingTimeoutProperties.connectTimeout,
				exchangeTimeoutMinutes = archivingTimeoutProperties.exchangeTimeout),
			clientAccessProperties = clientConfigurationProperties.registration["arkiv"]!!,
			oAuth2AccessTokenService = oAuth2AccessTokenService )
	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("archiveRestClient")
	fun archiveTestWebClient(@Value("\${joark.host}") joarkHost: String): RestClient = RestClient.builder().baseUrl(joarkHost).build()



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
		clientConfigProperties: ClientConfigurationProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService,
		fileFetchTimeoutProperties: FileFetchTimeoutProperties
	): RestClient {

		return restClientOAuth2Client(
			baseUrl = innsendingApiProperties.host,
			timeouts = timeouts(readTimeoutMinutes = fileFetchTimeoutProperties.readTimeout.toLong(), connectTimeoutSeconds = fileFetchTimeoutProperties.connectTimeout.toLong()),
			clientAccessProperties = clientConfigProperties.registration["innsendingApi"]!!,
			oAuth2AccessTokenService = oAuth2AccessTokenService
		)

	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("innsendingApiRestClient")
	fun innsendingApiClientWithoutOAuth(innsendingApiProperties: InnsendingApiProperties)
		= RestClient.builder().baseUrl(innsendingApiProperties.host).build()

	@Bean
	fun hentInnsendteFilerApi(
		innsendingApiProperties: InnsendingApiProperties,
		@Qualifier("innsendingApiRestClient") innsendingApiClient: RestClient): HentInnsendteFilerApi {
		//Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return HentInnsendteFilerApi(innsendingApiClient)
	}

	@Bean
	fun innsenderHealthApi(innsendingApiProperties: InnsendingApiProperties) = no.nav.soknad.innsending.api.HealthApi(innsendingApiProperties.host)

	private fun restClientOAuth2Client(
		baseUrl: String,
		timeouts: ClientHttpRequestFactory,
		clientAccessProperties: ClientProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService
	): RestClient {

		val tokenService = TokenService(clientAccessProperties, oAuth2AccessTokenService)

		return RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(timeouts)
			.requestInterceptor(RequestHeaderInterceptor(tokenService))
			.build()
	}

	class RequestHeaderInterceptor(val tokenService: TokenService) :
		ClientHttpRequestInterceptor {

		val logger: Logger = LoggerFactory.getLogger(javaClass)

		override fun intercept(
			request: HttpRequest,
			body: ByteArray,
			execution: ClientHttpRequestExecution
		): ClientHttpResponse {
			val token = tokenService.getToken()?.accessToken
			val callId = MDC.get(Constants.HEADER_CALL_ID)

			logger.info("Kaller service med callId: $callId")

			request.headers.setBearerAuth(token ?: "")
			request.headers.set(Constants.HEADER_CALL_ID, callId)
			request.headers.set(Constants.MDC_INNSENDINGS_ID, MDC.get(Constants.MDC_INNSENDINGS_ID) ?: "")

			return execution.execute(request, body)
		}
	}

}
