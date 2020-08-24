package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Scope
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate
import java.util.ArrayList

@Profile("spring | test")
@Configuration
//@EnableConfigurationProperties(ClientConfigurationProperties::class)
class ArchiveRestTemplateTestConfig(private val appConfiguration: AppConfiguration,
																		val objectMapper: ObjectMapper) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	@Profile("spring | test")
	@Qualifier("archiveRestTemplate")
	@Scope("prototype")
	@Lazy
	fun archiveRestTestTemplate(
		clientConfigurationProperties: ClientConfigurationProperties
	): RestTemplate? {
		logger.info("Initialiserer archiveRestTestTemplate. JoarkHost=${appConfiguration.config.joarkHost}")

		val properties: ClientProperties? = clientConfigurationProperties.registration?.get("soknadsarkiverer")
		logger.info("Token tokenEndpointUrl= ${properties?.tokenEndpointUrl}")

		val restTemplate = RestTemplate()
		val messageConverters = ArrayList<HttpMessageConverter<*>>()

		val jsonMessageConverter = MappingJackson2HttpMessageConverter()
		jsonMessageConverter.objectMapper = objectMapper
		messageConverters.add(jsonMessageConverter)

		restTemplate.messageConverters = messageConverters
		return restTemplate
	}

}
