package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import tools.jackson.databind.ObjectMapper
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer

class MutableListSerde : Serde<MutableList<String>> {
	override fun configure(configs: Map<String, *>, isKey: Boolean) {}
	override fun close() {}
	override fun serializer() = StringsListSerde()
	override fun deserializer() = StringsListSerde()
}

class StringsListSerde : Serializer<MutableList<String>>, Deserializer<MutableList<String>> {
	private var mapper: ObjectMapper? = ObjectMapper()

	override fun configure(configs: Map<String, *>, isKey: Boolean) {
		if (mapper == null) {
			mapper = ObjectMapper()
		}
	}

	override fun serialize(topic: String, data: MutableList<String>): ByteArray {
		return mapper!!.writeValueAsBytes(data)
	}

	override fun deserialize(topic: String, data: ByteArray?): MutableList<String> {
		if (data == null) {
			return mutableListOf()
		}
		return mapper!!.readValue(data, mutableListOf<String>().javaClass)
	}

	override fun close() {
		mapper = null
	}
}
