package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent

/**
 * Mapping helpers between the local JSON processing-event model and the existing Avro-generated
 * model. These exist to prove field- and vocabulary-level equivalence between the two
 * representations (see the mapping tests in `ProcessingEventJsonTests`).
 *
 * NOTE: Not used by any production or replay path yet. Wiring dual v2/v3 replay is left to a
 * later issue (#264); this issue only introduces the local JSON model and its serialization.
 */
fun ProcessingEvent.toProcessingEventJson(): ProcessingEventJson =
	ProcessingEventJson(ProcessingEventType.valueOf(type.name))

fun ProcessingEventJson.toAvroProcessingEvent(): ProcessingEvent =
	ProcessingEvent(EventTypes.valueOf(type.name))
