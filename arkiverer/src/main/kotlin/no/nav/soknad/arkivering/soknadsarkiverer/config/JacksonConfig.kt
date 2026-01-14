package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/*
Problem med Jackson and Kotlin data classes  i forbindelse med testing av overgang fra spring boot 3.x til 4.x
 */
@Configuration
class JacksonConfig : WebMvcConfigurer {

	@Bean
	fun objectMapper(): ObjectMapper {
		return jacksonObjectMapper()
	}
}
