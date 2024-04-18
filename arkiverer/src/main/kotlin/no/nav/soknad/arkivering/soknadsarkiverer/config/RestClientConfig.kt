package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.Constants
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileFetchTimeoutProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilestorageProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.InnsendingApiProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport.TokenService
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer
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
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.ReactorNettyClientRequestFactory
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
		clientConfigurationProperties: ClientConfigurationProperties
	): RestClient {

		return restClientOAuth2Client(
			baseUrl = joarkHost,
			timeouts = timeouts(readTimeoutMinutes = 4 * 60L, connectTimeoutSeconds = 2L),
			clientAccessProperties = clientConfigurationProperties.registration["arkiv"]!!,
			oAuth2AccessTokenService = oAuth2AccessTokenService )
	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("archiveRestClient")
	fun archiveTestWebClient(@Value("\${joark.host}") joarkHost: String): RestClient = RestClient.builder().baseUrl(joarkHost).build()



	private fun timeouts(readTimeoutMinutes: Long, connectTimeoutSeconds: Long): ReactorNettyClientRequestFactory {
		val factory = ReactorNettyClientRequestFactory()
		factory.setReadTimeout(Duration.ofMinutes(4 * 60L))
		factory.setConnectTimeout(Duration.ofSeconds(2L))
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
			timeouts = timeouts(fileFetchTimeoutProperties.readTimeout.toLong(), fileFetchTimeoutProperties.connectTimeout.toLong()),
			clientAccessProperties = clientConfigProperties.registration["innsendingApiRestClient"]!!,
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
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return HentInnsendteFilerApi(innsendingApiClient)
	}

	@Bean
	fun innsenderHealthApi(innsendingApiProperties: InnsendingApiProperties) = no.nav.soknad.innsending.api.HealthApi(innsendingApiProperties.host)

	@Bean
	@Profile("prod | dev")
	@Qualifier("filestorageRestClient")
	fun filestorageClient(
		filestorageProperties: FilestorageProperties,
		clientConfigProperties: ClientConfigurationProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService,
		fileFetchTimeoutProperties: FileFetchTimeoutProperties
	): RestClient {

		return restClientOAuth2Client(
			baseUrl = filestorageProperties.host,
			timeouts = timeouts(fileFetchTimeoutProperties.readTimeout.toLong(), fileFetchTimeoutProperties.connectTimeout.toLong()),
			clientAccessProperties = clientConfigProperties.registration["soknadsfillager"]!!,
			oAuth2AccessTokenService = oAuth2AccessTokenService )
	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("filestorageRestClient")
	fun filestorageClientWithoutOAuth(filestorageProperties: FilestorageProperties)
		= RestClient.builder().baseUrl(filestorageProperties.host).build()

	@Bean
	fun filesApi(
		filestorageProperties: FilestorageProperties,
		@Qualifier("filestorageRestClient") filestorageClient: RestClient
	): FilesApi {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		return FilesApi(filestorageClient)
	}

	@Bean
	fun healthApi(filestorageProperties: FilestorageProperties) = HealthApi(filestorageProperties.host)



	private fun restClientOAuth2Client(
		baseUrl: String,
		timeouts: ReactorNettyClientRequestFactory,
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
