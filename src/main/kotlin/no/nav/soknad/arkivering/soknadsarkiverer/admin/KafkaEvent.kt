package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.media.Schema
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import javax.validation.constraints.NotBlank

// NOTE! This class is exposed via a rest-service. Any changes to this class must be reflected in the frontend.
data class KafkaEvent<T>(
	@Schema(description = "Incrementing number beginning at 0. Each element in a list has its own number.",
		example = "0", required = true)
	@NotBlank
	val sequence: Int,

	@Schema(description = "A Soknadsarkivschema has a key which is used throughout the system.",
		example = "559ebdfb-d365-4495-b581-e60f99056a99", required = true)
	@NotBlank
	val innsendingKey: String,

	@Schema(description = "Each event on the Kafka topics has a unique messageId.",
		example = "81328274-d593-463a-89d1-309bc663a1a9", required = true)
	val messageId: String,

	@Schema(description = "Milliseconds since epoch, UTC",
		example = "1604334716511", required = true)
	val timestamp: Long,

	@Schema(description = "This denotes the type of Kafka event.",
		example = "STARTED", required = true)
	val type: PayloadType,

	@Schema(description = "The payload of the Kafka Event",
		example = "{\\\"type\\\": \\\"STARTED\\\"}", required = true)
	val content: T,
) {
	constructor(innsendingKey: String, messageId: String, timestamp: Long, payload: T):
		this(-1, innsendingKey, messageId, timestamp, getTypeRepresentation(payload), payload)
}

private fun getTypeRepresentation(data: Any?): PayloadType {
	return when(data) {
		is Soknadarkivschema -> PayloadType.INPUT
		is ProcessingEvent -> {
			when (data.getType()) {
				EventTypes.RECEIVED -> PayloadType.RECEIVED
				EventTypes.STARTED  -> PayloadType.STARTED
				EventTypes.ARCHIVED -> PayloadType.ARCHIVED
				EventTypes.FINISHED -> PayloadType.FINISHED
				else -> PayloadType.UNKNOWN
			}
		}
		is String -> {
			when {
				data.startsWith("ok", true) -> PayloadType.MESSAGE_OK
				data.startsWith("Exception", true) -> PayloadType.MESSAGE_EXCEPTION
				else -> PayloadType.UNKNOWN
			}
		}
		else -> PayloadType.UNKNOWN
	}
}

enum class PayloadType { INPUT, RECEIVED, STARTED, ARCHIVED, FINISHED, MESSAGE_OK, MESSAGE_EXCEPTION, UNKNOWN }
