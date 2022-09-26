package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConfigurationProperties(prefix = "filestorage")
@ConstructorBinding
data class FilestorageProperties(
	val host : String,
	val files : String,
	val username : String,
	val password: String
)
