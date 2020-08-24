package no.nav.soknad.arkivering.soknadsarkiverer.config

/*
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
*/
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
//@EnableOAuth2Client(cacheEnabled = true)
//@EnableJwtTokenValidation
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@Configuration
class ArchiveRestTemplateConfig(private val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	@Profile("prod | dev")
	@Qualifier("archiveRestTemplate")
	@Scope("prototype")
	@Lazy
fun archiveRestTemplate(restTemplateBuilder: RestTemplateBuilder,
												oAuth2AccessTokenService: OAuth2AccessTokenService,
												clientConfigurationProperties: ClientConfigurationProperties): RestTemplate? {
		val properties: ClientProperties? = clientConfigurationProperties.registration?.get("soknadsarkiverer")
		logger.info("Properties.tokenEndpointUrl= ${properties?.tokenEndpointUrl}")
		logger.info("appConfiguration.config.tokenEndpointUrl= ${appConfiguration.config.tokenEndpointUrl}")

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

/*
	@Bean
	fun getClientProperties(appConfiguration: AppConfiguration): ClientProperties {
		val authentication = ClientAuthenticationProperties(appConfiguration.config.username, ClientAuthenticationMethod(appConfiguration.config.tokenAuthenticationMethod), appConfiguration.kafkaConfig.password, null)
		return ClientProperties(URI.create(appConfiguration.config.tokenEndpointUrl), OAuth2GrantType.CLIENT_CREDENTIALS, appConfiguration.config.scopes, authentication, null) //TODO sjekk resourceUrl
	}
*/
/*
	@Target(ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE,	ElementType.ANNOTATION_TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@Qualifier
	annotation class ArchiveRestTemplate
*/
/*
	@Bean
	@ConfigurationProperties(prefix = "no.nav.security.jwt.client", ignoreUnknownFields = false)
	fun setAppProperties(): ClientConfigurationProperties? {
		val registration = mutableMapOf("soknadsarkiverer" to getClientProperties(appConfiguration) )
		return ClientConfigurationProperties(registration)
	}
*/
}
