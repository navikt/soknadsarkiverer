package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

@Service
class KafkaPublisher(private val appConfiguration: AppConfiguration) {

	private val kafkaProcessingEventProducer = KafkaProducer<String, ProcessingEvent>(kafkaConfigMap())
	private val kafkaMessageProducer = KafkaProducer<String, String>(kafkaConfigMap().also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })

	fun putProcessingEventOnTopic(key: String, value: ProcessingEvent, headers: Headers = RecordHeaders()) {
		val topic = appConfiguration.kafkaConfig.processingTopic
		val kafkaProducer = kafkaProcessingEventProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putMessageOnTopic(key: String?, value: String, headers: Headers = RecordHeaders()) {
		val topic = appConfiguration.kafkaConfig.messageTopic
		val kafkaProducer = kafkaMessageProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun <T> putDataOnTopic(key: String?, value: T, headers: Headers, topic: String,
																 kafkaProducer: KafkaProducer<String, T>): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add(MESSAGE_ID, UUID.randomUUID().toString().toByteArray())
		headers.forEach { h -> producerRecord.headers().add(h) }

		return kafkaProducer
			.send(producerRecord)
			.get(9000, TimeUnit.MILLISECONDS) // Blocking call
	}

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
			if ("TRUE" == appConfiguration.kafkaConfig.secure) {
				it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
				it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
				it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
			}
		}
	}
}
