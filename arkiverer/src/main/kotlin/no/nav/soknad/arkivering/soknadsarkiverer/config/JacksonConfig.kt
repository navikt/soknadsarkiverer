package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.databind.DeserializationFeature
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/*
Problem med Jackson and Kotlin data classes  i forbindelse med testing av overgang fra spring boot 3.x til 4.x
 */
@Configuration
class JacksonConfig : WebMvcConfigurer {

	@Bean
	fun objectMapper(): ObjectMapper = jacksonObjectMapper()
		.registerModule(JavaTimeModule())
		.registerKotlinModule()
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)

}
