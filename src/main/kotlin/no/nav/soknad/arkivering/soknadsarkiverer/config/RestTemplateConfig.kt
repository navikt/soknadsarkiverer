package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate

@Configuration
class RestTemplateConfig(val objectMapper: ObjectMapper) {

	@Bean
	fun createRestTemplate(): RestTemplate {
		val restTemplate = RestTemplate()
		val messageConverters = ArrayList<HttpMessageConverter<*>>()

		val jsonMessageConverter = MappingJackson2HttpMessageConverter()
		jsonMessageConverter.objectMapper = objectMapper
		messageConverters.add(jsonMessageConverter)

		restTemplate.messageConverters = messageConverters
		return restTemplate
	}
}
