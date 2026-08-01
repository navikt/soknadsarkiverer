package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.soknadsarkiverer.util.createUtcPreservingMapper
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingMetrics as InnsendingMetricsJson

/**
 * Kafka Serde for the generated soknadsmottaker OpenAPI [InnsendingMetricsJson] model (issue #265).
 * Encodes/decodes plain JSON using Jackson (with the `OffsetDateTime`-aware mapper also used for
 * `InnsendingTopicMsg`, see [createUtcPreservingMapper]) and does not require Schema Registry.
 */
class InnsendingMetricsJsonSerde : Serde<InnsendingMetricsJson> {
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
	override fun close() {}
	override fun serializer(): Serializer<InnsendingMetricsJson> = InnsendingMetricsJsonSerializer()
	override fun deserializer(): Deserializer<InnsendingMetricsJson> = InnsendingMetricsJsonDeserializer()
}

class InnsendingMetricsJsonSerializer : Serializer<InnsendingMetricsJson> {
	private val mapper: ObjectMapper = createUtcPreservingMapper()

	override fun serialize(topic: String?, data: InnsendingMetricsJson?): ByteArray? {
		if (data == null) return null
		return mapper.writeValueAsBytes(data)
	}
}

class InnsendingMetricsJsonDeserializer : Deserializer<InnsendingMetricsJson> {
	private val mapper: ObjectMapper = createUtcPreservingMapper()

	override fun deserialize(topic: String?, data: ByteArray?): InnsendingMetricsJson? {
		if (data == null) return null
		return mapper.readValue(data, InnsendingMetricsJson::class.java)
	}
}
