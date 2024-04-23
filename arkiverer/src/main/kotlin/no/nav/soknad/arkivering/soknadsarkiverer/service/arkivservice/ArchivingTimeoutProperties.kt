package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "archivingtimeouts")
data class ArchivingTimeoutProperties (
	val connectTimeout: Long,
	val readTimeout: Long,
	val exchangeTimeout: Long
)
