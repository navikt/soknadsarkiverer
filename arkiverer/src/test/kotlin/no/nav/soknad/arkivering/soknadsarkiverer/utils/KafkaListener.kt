package no.nav.soknad.arkivering.soknadsarkiverer.utils

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.InnsendingMetricsJsonSerde
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJson
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJsonSerde
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
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingMetrics as InnsendingMetricsJson

class KafkaListener(private val kafkaConfig: KafkaConfig) {

	private val logger = LoggerFactory.getLogger(javaClass)
	private val verbose = true

	private val metricsReceived          						= CopyOnWriteArrayList<Pair<Key, InnsendingMetricsJson>>()
	private val messagesReceived         						= CopyOnWriteArrayList<Pair<Key, String>>()
	private val arkiveringstilbakemeldingerReceived	= CopyOnWriteArrayList<Pair<Key, String>>()
	private val processingEventsReceived						= CopyOnWriteArrayList<Pair<Key, ProcessingEventJson>>()

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
		// Both streams read the v3 JSON topics (issue #265): production only writes there now, so
		// listening on the v2 Avro topics would no longer see anything.
		val metricsStream              = streamsBuilder.stream(kafkaConfig.topics.metricsTopicV3,    Consumed.with(stringSerde, InnsendingMetricsJsonSerde()))
		val processingEventTopicStream = streamsBuilder.stream(kafkaConfig.topics.processingTopicV3, Consumed.with(stringSerde, ProcessingEventJsonSerde()))
		val messagesStream             = streamsBuilder.stream(kafkaConfig.topics.messageTopic,    Consumed.with(stringSerde, stringSerde))
		val arkiveringstilbakemeldingerStream = streamsBuilder.stream(kafkaConfig.topics.arkiveringstilbakemeldingTopic,    Consumed.with(stringSerde, stringSerde))


		metricsStream
			.peek { key, metrics -> log("$key: Metrics received  - $metrics") }
			.foreach { key, metrics -> metricsReceived.add(key to metrics) }

		messagesStream
			.peek { key, message -> log("$key: Message received  - $message") }
			.foreach { key, message -> messagesReceived.add(key to message) }

		arkiveringstilbakemeldingerStream
			.peek { key, arkiveringstilbakemelding -> log("$key: Arkiveringstilbakemelding received  - $arkiveringstilbakemelding") }
			.foreach { key, arkiveringstilbakemelding -> arkiveringstilbakemeldingerReceived.add(key to arkiveringstilbakemelding) }

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
