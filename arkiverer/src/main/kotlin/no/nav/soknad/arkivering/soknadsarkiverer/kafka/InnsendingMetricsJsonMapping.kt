package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import java.time.Instant
import java.time.ZoneOffset
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingMetrics as InnsendingMetricsJson

/**
 * Maps the existing Avro-generated metrics model to the generated soknadsmottaker OpenAPI model
 * (issue #265). `startTime` changes representation from epoch-milliseconds (Avro `Long`) to an
 * ISO-8601 `OffsetDateTime` (OpenAPI `date-time`), so it is converted rather than copied as-is;
 * `application`, `action` and `duration` map across unchanged.
 */
fun InnsendingMetrics.toInnsendingMetricsJson(): InnsendingMetricsJson =
	InnsendingMetricsJson(
		application = application,
		action = action,
		startTime = Instant.ofEpochMilli(startTime).atOffset(ZoneOffset.UTC),
		duration = duration
	)
