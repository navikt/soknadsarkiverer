package no.nav.soknad.arkivering.soknadsarkiverer.admin

import java.time.LocalDateTime

data class KafkaEventRaw<T>(val key: String, val messageId: String, val timestamp: LocalDateTime, val payload: T)
