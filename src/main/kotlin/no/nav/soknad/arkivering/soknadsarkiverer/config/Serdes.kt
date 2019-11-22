package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.dto.ArchivalData
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer

class ArchivalDataSerde : Serde<ArchivalData> {
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
	override fun close() {}
	override fun deserializer(): Deserializer<ArchivalData> = ArchivalDataDeserializer()
	override fun serializer(): Serializer<ArchivalData> = ArchivalDataSerializer()
}

class ArchivalDataSerializer : Serializer<ArchivalData> {
	override fun serialize(topic: String, data: ArchivalData?): ByteArray? {
		if (data == null)
			return null
		val objectMapper = ObjectMapper()
		return objectMapper.writeValueAsBytes(data)
	}

	override fun close() {}
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
}

class ArchivalDataDeserializer : Deserializer<ArchivalData> {
	override fun deserialize(topic: String, data: ByteArray?): ArchivalData? {
		if (data == null)
			return null
		val objectMapper = ObjectMapper()
		return objectMapper.readValue(data, ArchivalData::class.java)
	}

	override fun close() {}
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
}
