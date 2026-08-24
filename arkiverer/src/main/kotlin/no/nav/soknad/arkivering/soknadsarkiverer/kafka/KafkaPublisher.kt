package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
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
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingMetrics as InnsendingMetricsJson

@Service
class KafkaPublisher(private val kafkaConfig: KafkaConfig) {

	// Production writers only ever publish plain JSON to the v3 topics from here on (issue #265):
	// processingeventlog-v2 and metrics-v2 are read-only from now on (see KafkaBootstrapConsumer,
	// which still replays their retained history). No Schema Registry is required for these.
	private val kafkaProcessingEventV3Producer = KafkaProducer<String, ProcessingEventJson>(kafkaConfigMap().also {
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ProcessingEventJsonSerializer::class.java
	})
	private val kafkaMetricsV3Producer = KafkaProducer<String, InnsendingMetricsJson>(kafkaConfigMap().also {
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = InnsendingMetricsJsonSerializer::class.java
	})
	private val kafkaMessageProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
	})
	private val kafkaArkiveringstilbakemeldingProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
	})

	fun putProcessingEventOnTopic(key: String, value: ProcessingEvent, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.processingTopicV3
		putDataOnTopic(key, value.toProcessingEventJson(), headers, topic, kafkaProcessingEventV3Producer)
	}

	fun putMessageOnTopic(key: String?, value: String?, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.messageTopic
		val kafkaProducer = kafkaMessageProducer
		putDataOnTopic(key, value?: "Empty message", headers, topic, kafkaProducer)
	}

	fun putArkiveringstilbakemeldingOnTopic(key: String?, value: String, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.arkiveringstilbakemeldingTopic
		val kafkaProducer = kafkaArkiveringstilbakemeldingProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putMetricOnTopic(key: String?, value: InnsendingMetrics, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.metricsTopicV3
		putDataOnTopic(key, value.toInnsendingMetricsJson(), headers, topic, kafkaMetricsV3Producer)
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
