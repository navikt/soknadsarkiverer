package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotBlank

data class KafkaEvent(
	@Schema(description = "Incrementing number beginning at 0. Each element in a list has its own number.",
		example = "1", required = true)
	@NotBlank
	val sequence: Int,

	@Schema(description = "A Soknadsarkivschema has a key which is used throughout the system.",
		example = "559ebdfb-d365-4495-b581-e60f99056a99", required = true)
	@NotBlank
	val innsendingKey: String,

	@Schema(description = "Each event on the Kafka topics has a unique messageId.",
		example = "81328274-d593-463a-89d1-309bc663a1a9", required = true)
	val messageId: String,

	@Schema(description = "This denotes the type of Kafka event.", // TODO: Introduce enum? enum class Type { INPUT, RECEIVED, STARTED, ARCHIVED, FINISHED, MESSAGE_OK, MESSAGE_EXCEPTION }
		example = "Input", required = true)
	val type: String,

	@Schema(description = "Milliseconds since epoch, UTC",
		example = "1604334716511", required = true)
	val timestamp: Long
)
