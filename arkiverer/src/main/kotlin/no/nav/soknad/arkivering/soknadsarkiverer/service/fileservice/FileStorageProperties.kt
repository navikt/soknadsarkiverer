package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "filestorage")
data class FilestorageProperties(
	val host : String,
	val files : String,
)
