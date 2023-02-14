package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.utility.LeaderSelectionUtility
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.TimeUnit

@Service
class KafkaPublisher(
	private val kafkaConfig: KafkaConfig,
	private val topicSelection: TopicSelection) {

	private val kafkaProcessingEventProducer = KafkaProducer<String, ProcessingEvent>(kafkaConfigMap())
	private val kafkaMetricsProducer = KafkaProducer<String, InnsendingMetrics>(kafkaConfigMap())
	private val kafkaMessageProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
	})

	fun putProcessingEventOnTopic(key: String, value: ProcessingEvent, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.processingTopic
		val kafkaProducer = kafkaProcessingEventProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putMessageOnTopic(key: String?, value: String, headers: Headers = RecordHeaders()) {
		val topic = topicSelection.selectTopicVersion(TopicTypes.MESSAGES_TOPIC, kafkaConfig)
		val kafkaProducer = kafkaMessageProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putMetricOnTopic(key: String?, value: InnsendingMetrics, headers: Headers = RecordHeaders()) {
		val topic = topicSelection.selectTopicVersion(TopicTypes.METRICS_TOPIC, kafkaConfig)
		val kafkaProducer = kafkaMetricsProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun <T> putDataOnTopic(
		key: String?, value: T, headers: Headers, topic: String,
		kafkaProducer: KafkaProducer<String, T>
	): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add(MESSAGE_ID, UUID.randomUUID().toString().toByteArray())
		headers.forEach { h -> producerRecord.headers().add(h) }

		return kafkaProducer
			.send(producerRecord)
			.get(9000, TimeUnit.MILLISECONDS) // Blocking call
	}

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = kafkaConfig.schemaRegistry.url
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
			if (kafkaConfig.security.enabled == "TRUE") {
				it[SchemaRegistryClientConfig.USER_INFO_CONFIG] = "${kafkaConfig.schemaRegistry.username}:${kafkaConfig.schemaRegistry.password}"
				it[SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE] = "USER_INFO"
				it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = kafkaConfig.security.protocol
				it[SslConfigs.SSL_KEYSTORE_TYPE_CONFIG] = "PKCS12"
				it[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] = kafkaConfig.security.trustStorePath
				it[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = kafkaConfig.security.trustStorePassword
				it[SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG] = kafkaConfig.security.keyStorePath
				it[SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
				it[SslConfigs.SSL_KEY_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
			}
		}
	}
}
