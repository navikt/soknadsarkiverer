package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "innsendingapi")
data class InnsendingApiProperties(
	val host : String,
)
