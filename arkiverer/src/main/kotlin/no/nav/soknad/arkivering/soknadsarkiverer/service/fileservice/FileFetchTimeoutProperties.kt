package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "filefetchtimeouts")
data class FileFetchTimeoutProperties(
	val connectTimeout: String,
	val callTimeout: String,
	val readTimeout: String,
	val writeTimeout: String
)
