package no.nav.soknad.arkivering.soknadsarkiverer.utils

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class KafkaListener(private val kafkaConfig: KafkaConfig) {

	private val logger = LoggerFactory.getLogger(javaClass)
	private val verbose = true

	private val metricsReceived          = CopyOnWriteArrayList<Pair<Key, InnsendingMetrics>>()
	private val messagesReceived         = CopyOnWriteArrayList<Pair<Key, String>>()
	private val arkiveringstilbakemeldingerReceived         = CopyOnWriteArrayList<Pair<Key, String>>()
	private val processingEventsReceived = CopyOnWriteArrayList<Pair<Key, ProcessingEvent>>()

	private val kafkaStreams: KafkaStreams

	private val stringSerde = Serdes.StringSerde()

	init {
		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaConfig = kafkaConfig()
		kafkaStreams = KafkaStreams(topology, kafkaConfig)
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
	}


	private fun kafkaStreams(streamsBuilder: StreamsBuilder) {
		val metricsStream              = streamsBuilder.stream(kafkaConfig.topics.metricsTopic,    Consumed.with(stringSerde, createInnsendingMetricsSerde()))
		val processingEventTopicStream = streamsBuilder.stream(kafkaConfig.topics.processingTopic, Consumed.with(stringSerde, createProcessingEventSerde()))
		val messagesStream             = streamsBuilder.stream(kafkaConfig.topics.messageTopic,    Consumed.with(stringSerde, stringSerde))
		val arkiveringstilbakemeldingerStream = streamsBuilder.stream(kafkaConfig.topics.arkiveringstilbakemeldingTopic,    Consumed.with(stringSerde, stringSerde))


		metricsStream
			.peek { key, metrics -> log("$key: Metrics received  - $metrics") }
			.foreach { key, metrics -> metricsReceived.add(key to metrics) }

		messagesStream
			.peek { key, message -> log("$key: Message received  - $message") }
			.foreach { key, message -> messagesReceived.add(key to message) }

		arkiveringstilbakemeldingerStream
			.peek { key, message -> log("$key: Message received  - $message") }
			.foreach { key, message -> messagesReceived.add(key to message) }

		processingEventTopicStream
			.peek { key, entity -> log("$key: Processing Events - $entity") }
			.foreach { key, entity -> processingEventsReceived.add(key to entity) }
	}

	private fun log(message: String) {
		if (verbose)
			logger.info(message)
	}

	private fun kafkaConfig() = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = kafkaConfig.schemaRegistry.url
		it[StreamsConfig.APPLICATION_ID_CONFIG] = "soknadarkiverer-tests"
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = LogAndContinueExceptionHandler::class.java
		it[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG] = 1000

		if (kafkaConfig.security.enabled == "TRUE") {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = kafkaConfig.security.protocol
			it[SslConfigs.SSL_KEYSTORE_TYPE_CONFIG] = "PKCS12"
			it[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] = kafkaConfig.security.trustStorePath
			it[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = kafkaConfig.security.trustStorePassword
			it[SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG] = kafkaConfig.security.keyStorePath
			it[SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
			it[SslConfigs.SSL_KEY_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
		}
	}

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createInnsendingMetricsSerde(): SpecificAvroSerde<InnsendingMetrics> = createAvroSerde()

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig =
			hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to kafkaConfig.schemaRegistry.url)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}


	fun close() {
		kafkaStreams.close()
		kafkaStreams.cleanUp()
	}


	fun getMetrics() = metricsReceived.map { Record(it.first, it.second) }
	fun getMessages() = messagesReceived.map { Record(it.first, it.second) }
	fun getArkiveringstilbakemeldinger() = arkiveringstilbakemeldingerReceived.map { Record(it.first, it.second) }
	fun getProcessingEvents() = processingEventsReceived.map { Record(it.first, it.second) }

	data class Record<T>(val key: Key, val value: T)
}

typealias Key = String
