package no.nav.soknad.arkivering.soknadsarkiverer.kafka

/**
 * Local JSON model for processing events (JSON v3 processing-event contract, see issue #263).
 *
 * This mirrors the Avro-generated `no.nav.soknad.arkivering.avroschemas.ProcessingEvent` model
 * field-for-field and retains the exact event-type vocabulary used there, but is encoded as
 * plain JSON so that producing or consuming it does not require Schema Registry.
 *
 * NOTE: This model is not yet wired into any Kafka topic or replay path. Production and replay
 * behavior remain on v2 Avro (see [no.nav.soknad.arkivering.avroschemas.ProcessingEvent]) until
 * a later issue introduces the v3 wiring.
 */
data class ProcessingEventJson(val type: ProcessingEventType)

/**
 * Event-type vocabulary for processing events. Must remain identical to the symbols of the Avro
 * `EventTypes` enum: RECEIVED, STARTED, ARCHIVED, FINISHED, FAILURE. Processing-event semantics
 * and event-type values must not be redesigned as part of introducing this JSON model.
 */
enum class ProcessingEventType {
	RECEIVED, STARTED, ARCHIVED, FINISHED, FAILURE
}
