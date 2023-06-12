package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConfigurationProperties(prefix = "innsending-api")
@ConstructorBinding
data class InnsendingApiProperties(
	val host : String,
	val files : String,
)
