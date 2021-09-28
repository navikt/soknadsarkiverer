package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.media.Schema

data class Metrics(
	@Schema(description = "The application that sent out the metric.",
		example = "soknadsmottaker", required = true)
	val application: String,

	@Schema(description = "A string, denoting the action that the metric is for.",
		example = "send files to archive", required = true)
	val action: String,

	@Schema(description = "The time when the event started, in milliseconds since epoch, UTC",
		example = "1604334716511", required = true)
	val startTime: Long,

	@Schema(description = "The duration of the event in milliseconds.",
		example = "6871", required = true)
	val duration: Long
)

data class MetricsObject(
	@Schema(description = "A Soknadsarkivschema has a key which is used throughout the system.",
		example = "559ebdfb-d365-4495-b581-e60f99056a99", required = true)
	val key: String,

	@Schema(description = "A list of metrics belonging to the given key.",
		required = true)
	val datapoints: List<Metrics>
)
