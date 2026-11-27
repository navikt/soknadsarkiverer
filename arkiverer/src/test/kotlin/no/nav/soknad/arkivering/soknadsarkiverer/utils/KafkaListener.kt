package no.nav.soknad.arkivering.soknadsarkiverer.utils

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.InnsendingMetricsJsonSerde
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJson
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJsonSerde
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.processor.api.ContextualFixedKeyProcessor
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier
import org.apache.kafka.streams.processor.api.FixedKeyRecord
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingMetrics as InnsendingMetricsJson

class KafkaListener(
	private val kafkaConfig: KafkaConfig,
	private val applicationGroupId: String? = null
) : AutoCloseable {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val metricsReceived          						= CopyOnWriteArrayList<Pair<Key, InnsendingMetricsJson>>()
	private val messagesReceived         						= CopyOnWriteArrayList<Pair<Key, String>>()
	private val arkiveringstilbakemeldingerReceived	= CopyOnWriteArrayList<Pair<Key, String>>()
	private val processingEventsReceived						= CopyOnWriteArrayList<Pair<Key, ProcessingEventJson>>()

	// Next offset to read per partition, recorded after a record has been stored in the lists above.
	private val nextOffsets = ConcurrentHashMap<TopicPartition, Long>()
	private val listenedTopics = with(kafkaConfig.topics) {
		listOf(metricsTopicV3, processingTopicV3, messageTopic, arkiveringstilbakemeldingTopic)
	}

	internal val kafkaStreams: KafkaStreams

	private val stringSerde = Serdes.StringSerde()

	init {
		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaConfig = kafkaConfig()
		kafkaStreams = KafkaStreams(topology, kafkaConfig)
		try {
			kafkaStreams.start()
		} catch (e: Exception) {
			runCatching { close() }.exceptionOrNull()?.let(e::addSuppressed)
			throw e
		}
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
			.collectInto(metricsReceived)

		messagesStream
			.peek { key, message -> log("$key: Message received  - $message") }
			.collectInto(messagesReceived)

		arkiveringstilbakemeldingerStream
			.peek { key, arkiveringstilbakemelding -> log("$key: Arkiveringstilbakemelding received  - $arkiveringstilbakemelding") }
			.collectInto(arkiveringstilbakemeldingerReceived)

		processingEventTopicStream
			.peek { key, entity -> log("$key: Processing Events - $entity") }
			.collectInto(processingEventsReceived)
	}

	private fun <V : Any> KStream<String, V>.collectInto(received: MutableList<Pair<Key, V>>) {
		processValues(FixedKeyProcessorSupplier<String, V, V> {
			object : ContextualFixedKeyProcessor<String, V, V>() {
				override fun process(record: FixedKeyRecord<String, V>) {
					received.add(record.key() to record.value())
					context().recordMetadata().ifPresent {
						nextOffsets.merge(TopicPartition(it.topic(), it.partition()), it.offset() + 1, ::maxOf)
					}
				}
			}
		})
	}

	private fun log(message: String) {
		logger.debug(message)
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

	/**
	 * Waits until this listener has stored every record that is on the listened topics now.
	 * Call it after a completion barrier, before asserting exact counts or absence.
	 */
	fun awaitCaughtUp(timeout: Duration = Duration.ofSeconds(30)) {
		val endOffsets = admin().use { endOffsets(it, listenedTopics) }
		awaitCondition(timeout, { "Kafka listener did not catch up within $timeout. End offsets: $endOffsets, read: $nextOffsets" }) {
			endOffsets.all { (partition, endOffset) -> (nextOffsets[partition] ?: 0L) >= endOffset }
		}
	}

	/**
	 * Waits until the application under test has handled every processing event that is on the topic
	 * now. Kafka Streams commits an offset after it has handled the record, including the work done
	 * synchronously in the stream thread, such as finishing a task and lowering the task gauge.
	 * Requires the consumer group of the application, `KafkaSetupTest.groupId`, in the constructor.
	 */
	fun awaitApplicationCaughtUp(timeout: Duration = Duration.ofSeconds(30)) {
		val groupId = checkNotNull(applicationGroupId) { "Create the listener with the application's consumer group" }
		admin().use { admin ->
			val endOffsets = endOffsets(admin, listOf(kafkaConfig.topics.processingTopicV3))
			awaitCondition(timeout, { "The application did not handle the processing events within $timeout" }) {
				val committed = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
				endOffsets.all { (partition, endOffset) -> (committed[partition]?.offset() ?: 0L) >= endOffset }
			}
		}
	}

	private fun admin() = Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaConfig.brokers))

	private fun endOffsets(admin: Admin, topics: List<String>): Map<TopicPartition, Long> {
		val partitions = admin.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS).values
			.flatMap { topic -> topic.partitions().map { TopicPartition(topic.name(), it.partition()) } }
		return admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(10, TimeUnit.SECONDS)
			.mapValues { it.value.offset() }
	}

	/** Waits until at least [count] processing events of [type] have been seen for [key]. */
	fun awaitProcessingEvent(key: Key, type: String, count: Int = 1, timeout: Duration = Duration.ofSeconds(30)) {
		awaitCondition(timeout, { "Expected at least $count $type for $key within $timeout" }) {
			processingEventsReceived.count { it.first == key && it.second.type.name == type } >= count
		}
	}

	private fun awaitCondition(timeout: Duration, message: () -> String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + timeout.toNanos()
		while (!condition()) {
			check(System.nanoTime() < deadline, message)
			TimeUnit.MILLISECONDS.sleep(20)
		}
	}

	override fun close() = close(Duration.ofSeconds(30))

	internal fun close(timeout: Duration) {
		check(kafkaStreams.close(timeout)) {
			"Test Kafka listener did not close within $timeout"
		}
		kafkaStreams.cleanUp()
	}


	fun getMetrics() = metricsReceived.map { Record(it.first, it.second) }
	fun getMessages() = messagesReceived.map { Record(it.first, it.second) }
	fun getArkiveringstilbakemeldinger() = arkiveringstilbakemeldingerReceived.map { Record(it.first, it.second) }
	fun getProcessingEvents() = processingEventsReceived.map { Record(it.first, it.second) }

	data class Record<T>(val key: Key, val value: T)
}

typealias Key = String
