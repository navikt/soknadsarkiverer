package no.nav.soknad.arkivering.soknadsarkiverer.admin

import java.time.LocalDateTime

data class KafkaEvent(val key: String, val timestamp: LocalDateTime, val type: String)
