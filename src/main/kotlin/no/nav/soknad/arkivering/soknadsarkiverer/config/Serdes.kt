package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.*
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificRecordBase
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import java.io.ByteArrayOutputStream
import java.io.IOException

class SoknadMottattDtoSerde : Serde<Soknadarkivschema> {
	override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
	override fun close() {}
	override fun deserializer(): Deserializer<Soknadarkivschema> = AvroDeserializer(Soknadarkivschema::class.java)
	override fun serializer(): Serializer<Soknadarkivschema> = AvroSerializer()
}

class AvroSerializer<T : SpecificRecordBase> : Serializer<T> {

	override fun serialize(topic: String, data: T?): ByteArray? {
		return try {
			var result: ByteArray? = null
			if (data != null) {

				val byteArrayOutputStream = ByteArrayOutputStream()
				byteArrayOutputStream.use {

					val binaryEncoder = EncoderFactory.get().binaryEncoder(byteArrayOutputStream, null)
					val datumWriter: DatumWriter<GenericRecord> = GenericDatumWriter(data.schema)

					datumWriter.write(data, binaryEncoder)
					binaryEncoder.flush()

					result = byteArrayOutputStream.toByteArray()
				}
			}
			result
		} catch (ex: IOException) {
			throw SerializationException("Can't serialize data='$data' for topic='$topic'", ex)
		}
	}

	override fun close() {}
	override fun configure(arg0: Map<String?, *>?, arg1: Boolean) {}
}

class AvroDeserializer<T : SpecificRecordBase>(private val targetType: Class<T>) : Deserializer<T> {
	override fun deserialize(topic: String, data: ByteArray): T {
		try {
			val datumReader: DatumReader<GenericRecord> = SpecificDatumReader(targetType.getDeclaredConstructor().newInstance().schema)
			val decoder: Decoder = DecoderFactory.get().binaryDecoder(data, null)
			return datumReader.read(null, decoder) as T

		} catch (ex: Exception) {
			throw SerializationException("Can't deserialize data '" + data.contentToString() + "' from topic '" + topic + "'", ex)
		}
	}

	override fun close() {}
	override fun configure(arg0: Map<String?, *>?, arg1: Boolean) {}
}
