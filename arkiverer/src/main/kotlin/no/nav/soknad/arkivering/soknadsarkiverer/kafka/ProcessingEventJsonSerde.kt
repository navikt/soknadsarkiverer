package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer

/**
 * Kafka Serde for [ProcessingEventJson]. Encodes/decodes plain JSON using Jackson and does not
 * require Schema Registry.
 *
 * NOTE: Not yet wired into any Kafka topic, producer or consumer. Production and replay wiring
 * remain on the Avro `ProcessingEvent`/`SpecificAvroSerde` (see [KafkaStreamsSetup] and
 * [KafkaPublisher]) until a later issue introduces the v3 topic wiring (see issue #263 and #264).
 */
class ProcessingEventJsonSerde : Serde<ProcessingEventJson> {
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
	override fun close() {}
	override fun serializer(): Serializer<ProcessingEventJson> = ProcessingEventJsonSerializer()
	override fun deserializer(): Deserializer<ProcessingEventJson> = ProcessingEventJsonDeserializer()
}

class ProcessingEventJsonSerializer : Serializer<ProcessingEventJson> {
	private val mapper: ObjectMapper = jacksonObjectMapper()

	override fun serialize(topic: String?, data: ProcessingEventJson?): ByteArray? {
		if (data == null) return null
		return mapper.writeValueAsBytes(data)
	}
}

class ProcessingEventJsonDeserializer : Deserializer<ProcessingEventJson> {
	private val mapper: ObjectMapper = jacksonObjectMapper()

	override fun deserialize(topic: String?, data: ByteArray?): ProcessingEventJson? {
		if (data == null) return null
		return mapper.readValue(data, ProcessingEventJson::class.java)
	}
}
