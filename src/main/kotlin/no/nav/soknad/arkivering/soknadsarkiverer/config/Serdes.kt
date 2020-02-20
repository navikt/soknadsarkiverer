package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import no.nav.soknad.arkivering.dto.SoknadMottattDto
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer

class SoknadMottattDtoSerde : Serde<SoknadMottattDto> {
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
	override fun close() {}
	override fun deserializer(): Deserializer<SoknadMottattDto> = SoknadMottattDtoDeserializer()
	override fun serializer(): Serializer<SoknadMottattDto> = SoknadMottattDtoSerializer()
}

class SoknadMottattDtoSerializer : Serializer<SoknadMottattDto> {
	override fun serialize(topic: String, data: SoknadMottattDto?): ByteArray? {
		if (data == null)
			return null
		return objectMapper.writeValueAsBytes(data)
	}

	override fun close() {}
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
}

class SoknadMottattDtoDeserializer : Deserializer<SoknadMottattDto> {
	override fun deserialize(topic: String, data: ByteArray?): SoknadMottattDto? {
		if (data == null)
			return null
		return objectMapper.readValue(data, SoknadMottattDto::class.java)
	}

	override fun close() {}
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
}

val objectMapper = ObjectMapper().also {
	it.registerModule(ParameterNamesModule(JsonCreator.Mode.PROPERTIES))
	it.registerModule(JavaTimeModule())
}
