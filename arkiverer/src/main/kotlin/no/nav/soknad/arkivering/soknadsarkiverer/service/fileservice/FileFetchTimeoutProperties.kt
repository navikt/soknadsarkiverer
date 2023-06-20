package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConfigurationProperties(prefix = "filefetchtimeouts")
@ConstructorBinding
data class FileFetchTimeoutProperties(
	val connectTimeout: String,
	val callTimeout: String,
	val readTimeout: String,
	val writeTimeout: String
)
