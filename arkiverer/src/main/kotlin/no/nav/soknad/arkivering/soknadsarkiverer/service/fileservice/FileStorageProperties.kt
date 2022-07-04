package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConfigurationProperties(prefix = "filestorage")
@ConstructorBinding
data class FileStorageProperties(
	val host : String,
	val files : String,
	val username : String,
	val password: String
)
