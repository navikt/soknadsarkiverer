package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import no.nav.security.token.support.client.core.ClientAuthenticationProperties
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.OAuth2GrantType
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.security.token.support.client.spring.oauth2.EnableOAuth2Client
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import java.net.URI
import java.util.*


@EnableOAuth2Client(cacheEnabled = true)
@EnableJwtTokenValidation
@Configuration
class ArchiveRestTemplateConfig(private val appConfiguration: AppConfiguration, val objectMapper: ObjectMapper) {

/*
	@Bean
	@Qualifier("archiveConfigurationProperties")
	fun getClientConfigurationProperties(): ClientConfigurationProperties {
		return ClientConfigurationProperties( mapOf("journalpost" to getClientProperties(appConfiguration)) )
	}

*/
	@Bean
	@Profile("prod | test")
	@Qualifier("archiveRestTemplate")
	fun archiveRestTemplate(restTemplateBuilder: RestTemplateBuilder,
													clientConfigurationProperties: ClientConfigurationProperties,
													oAuth2AccessTokenService: OAuth2AccessTokenService): RestTemplate? {
		val properties: ClientProperties? = getClientProperties(appConfiguration)
		val clientProperties: ClientProperties = Optional.ofNullable(properties)
			.orElseThrow( { RuntimeException("could not find oauth2 client config for archiveRestTemplate") })
		return restTemplateBuilder
			.additionalInterceptors(bearerTokenInterceptor(clientProperties, oAuth2AccessTokenService))
			.build()
	}

	@Bean
	@Profile("spring")
	@Qualifier("archiveRestTemplate")
	fun archiveRestTestTemplate(): RestTemplate {
		val restTemplate = RestTemplate()
		val messageConverters = ArrayList<HttpMessageConverter<*>>()

		val jsonMessageConverter = MappingJackson2HttpMessageConverter()
		jsonMessageConverter.objectMapper = objectMapper
		messageConverters.add(jsonMessageConverter)

		restTemplate.messageConverters = messageConverters
		return restTemplate
	}

	private fun bearerTokenInterceptor(clientProperties: ClientProperties,
																		 oAuth2AccessTokenService: OAuth2AccessTokenService): ClientHttpRequestInterceptor? {
		return ClientHttpRequestInterceptor { request: HttpRequest, body: ByteArray?, execution: ClientHttpRequestExecution ->
			val response: OAuth2AccessTokenResponse = oAuth2AccessTokenService.getAccessToken(clientProperties)
			request.headers.setBearerAuth(response.getAccessToken())
			execution.execute(request, body!!)
		}
	}

	@Bean
	fun getClientProperties(appConfiguration: AppConfiguration): ClientProperties {
		val authentication = ClientAuthenticationProperties(appConfiguration.config.username, ClientAuthenticationMethod("client_secret_basic"), appConfiguration.config.sharedPassword, null )
		return ClientProperties(URI.create(appConfiguration.config.tokenEndpointUrl), OAuth2GrantType("urn:ietf:params:oauth:grant-type:jwt-bearer"), listOf("scope"), authentication, null) //TODO sjekk scope og resourceUrl
	}


	@Target(ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE,	ElementType.ANNOTATION_TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@Qualifier
	annotation class ArchiveRestTemplate

}
