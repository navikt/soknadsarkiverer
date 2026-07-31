package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Verifies the local JSON processing-event model (introduced in issue #263):
 * - It can be round-tripped through the Kafka [ProcessingEventJsonSerde] (and plain Jackson)
 *   without Schema Registry.
 * - It is equivalent, field-for-field and value-for-value, to the existing Avro-generated
 *   `ProcessingEvent`/`EventTypes` model, so switching serialization does not change semantics.
 *
 * This does not wire the JSON model into any topic or replay path; production/replay remain on
 * v2 Avro.
 */
class ProcessingEventJsonTests {

	private val mapper = jacksonObjectMapper()
	private val serde = ProcessingEventJsonSerde()

	@ParameterizedTest
	@EnumSource(ProcessingEventType::class)
	fun `Serde round-trips every event type without Schema Registry`(eventType: ProcessingEventType) {
		val original = ProcessingEventJson(eventType)

		val serialized = serde.serializer().serialize("some-topic", original)
		val deserialized = serde.deserializer().deserialize("some-topic", serialized)

		assertEquals(original, deserialized)
	}

	@ParameterizedTest
	@EnumSource(ProcessingEventType::class)
	fun `Plain Jackson round-trips every event type as JSON text`(eventType: ProcessingEventType) {
		val original = ProcessingEventJson(eventType)

		val json = mapper.writeValueAsString(original)
		val deserialized = mapper.readValue(json, ProcessingEventJson::class.java)

		assertEquals(original, deserialized)
		assertEquals("""{"type":"${eventType.name}"}""", json)
	}

	@Test
	fun `Serializing null yields null, deserializing null yields null`() {
		assertEquals(null, serde.serializer().serialize("some-topic", null))
		assertEquals(null, serde.deserializer().deserialize("some-topic", null))
	}

	@Test
	fun `Event-type vocabulary is exactly RECEIVED, STARTED, ARCHIVED, FINISHED, FAILURE`() {
		val expected = setOf("RECEIVED", "STARTED", "ARCHIVED", "FINISHED", "FAILURE")

		assertEquals(expected, ProcessingEventType.values().map { it.name }.toSet())
	}

	@Test
	fun `JSON event-type vocabulary matches the Avro-generated EventTypes vocabulary exactly`() {
		val jsonNames = ProcessingEventType.values().map { it.name }.toSet()
		val avroNames = EventTypes.values().map { it.name }.toSet()

		assertEquals(avroNames, jsonNames)
	}

	@ParameterizedTest
	@EnumSource(EventTypes::class)
	fun `Mapping an Avro ProcessingEvent to JSON and back is a round trip`(avroType: EventTypes) {
		val avroEvent = ProcessingEvent(avroType)

		val json = avroEvent.toProcessingEventJson()
		val mappedBack = json.toAvroProcessingEvent()

		assertEquals(avroType.name, json.type.name)
		assertEquals(avroEvent, mappedBack)
	}

	@ParameterizedTest
	@EnumSource(ProcessingEventType::class)
	fun `Mapping a JSON ProcessingEvent to Avro and back is a round trip`(jsonType: ProcessingEventType) {
		val jsonEvent = ProcessingEventJson(jsonType)

		val avroEvent = jsonEvent.toAvroProcessingEvent()
		val mappedBack = avroEvent.toProcessingEventJson()

		assertEquals(jsonType.name, avroEvent.type.name)
		assertEquals(jsonEvent, mappedBack)
	}

	@Test
	fun `Serialized JSON bytes deserialize into an Avro-equivalent event for every vocabulary value`() {
		EventTypes.values().forEach { avroType ->
			val avroEvent = ProcessingEvent(avroType)

			val serialized = serde.serializer().serialize("some-topic", avroEvent.toProcessingEventJson())
			val deserialized = serde.deserializer().deserialize("some-topic", serialized)

			assertEquals(avroEvent, deserialized!!.toAvroProcessingEvent())
		}
	}
}
