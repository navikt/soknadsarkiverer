package no.nav.soknad.arkivering.soknadsarkiverer.admin

data class KafkaEvent(val sequence: Int, val innsendingKey: String, val messageId: String, val type: String, val timestamp: Long)
