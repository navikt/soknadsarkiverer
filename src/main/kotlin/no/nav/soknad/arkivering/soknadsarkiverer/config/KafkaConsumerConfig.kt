package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FileStorageRetrievingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.apache.kafka.common.serialization.IntegerDeserializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.*

@Configuration
class KafkaConsumerConfig(val applicationProperties: ApplicationProperties,
													val fileStorageRetrievingService: FileStorageRetrievingService,
													val messageConverter: MessageConverter,
													val joarkArchiver: JoarkArchiver) {

	private val logger = LoggerFactory.getLogger(javaClass)

	fun kafkaConfig(applicationId: String) = Properties().also {
		it[RETRY_TOPIC] = applicationProperties.kafkaRetryTopic
		it[DEAD_LETTER_TOPIC] = applicationProperties.kafkaDeadLetterTopic
		it[KAFKA_MAX_RETRY_COUNT] = applicationProperties.kafkaMaxRetryCount
		it[StreamsConfig.APPLICATION_ID_CONFIG] = applicationId
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = applicationProperties.kafkaBootstrapServers
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
	}

	private fun setupTopology(kstream: KStream<KafkaMsgWrapper.Value<KafkaMsg>, ArchivalData>) {

		kstream
			.map { msg, archivalData  -> w(msg) { archivalData to fileStorageRetrievingService.getFilesFromFileStorage(archivalData) } }
			.map { msg, dataAndFiles  -> w(msg) { messageConverter.createJoarkData(dataAndFiles?.first!!, dataAndFiles.second) } }
			.foreach { msg, joarkData -> w(msg) { joarkArchiver.putDataInJoark(joarkData!!) } }
	}

	/**
	 * This function will execute the given lambda function. If anything goes wrong and we get an exception, the Kafka
	 * record will be put on the retry topic.
	 *
	 * Apart from the lambda function to be executed, the function also takes in a wrapper that serves two purposes.
	 * Its type is either KafkaMsgWrapper.Value or KafkaMsgWrapper.None. If it is of type KafkaMsgWrapper.None,
	 * that means that a previous step has failed. Upon that failure, the message has been put on the retry topic.
	 * We want to gracefully continue the execution of the message, and for that reason we pass on the empty
	 * KafkaMsgWrapper.None to the other steps in the topology.
	 * If an exception occurs when executing the lambda function, the original message can be accessed from the wrapper.
	 * When the exception happens, the original message is retrieved from the wrapper, put on the retry topic, and the
	 * function emits a KafkaMsgWrapper.None to signal to the other topology steps that no further processing can be done.
	 */
	private fun <T> w(originalMessage: KafkaMsgWrapper<KafkaMsg>, function: () -> T): KeyValue<KafkaMsgWrapper<KafkaMsg>, T?> {

		if (originalMessage is KafkaMsgWrapper.Value) { // Enter only if an exception has not occurred on a previous step in the topology
			try {
				return KeyValue(originalMessage, function.invoke())

			} catch (e: Exception) {
				val (orig) = originalMessage
				kafkaExceptionHandler().retry(orig)
			}
		}
		return KeyValue(KafkaMsgWrapper.None, null)
	}

	fun kafkaStreamTopology(): Topology {
		val topic = applicationProperties.kafkaTopic

		val streamsBuilder = StreamsBuilder()
		val kStream = streamsBuilder.stream<String, ArchivalData>(topic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
			.transform(TransformerSupplier { WrapperProcessor() })
			.peek { kafkaMsg, _ -> logger.info("Received main event: ${kafkaMsg.t}") }

		setupTopology(kStream)
		return streamsBuilder.build()
	}

	fun kafkaRetryTopology(): Topology {
		val topic = applicationProperties.kafkaRetryTopic

		val streamsBuilder = StreamsBuilder()
		val windowedStream = streamsBuilder.stream<String, ArchivalData>(topic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
			.groupBy { key, value -> KeyValue(key, value) }
			.windowedBy(TimeWindows.of(Duration.ofSeconds(1L)))
			.count()
			.toStream()

		val kStream = windowedStream
			.map { key, _ -> KeyValue(key.key().key, key.key().value) }
			.transform(TransformerSupplier { WrapperProcessor() })
			.peek { kafkaMsg, _ -> logger.info("Received retry event: ${kafkaMsg.t}") }

		setupTopology(kStream)
		return streamsBuilder.build()
	}

	@Bean
	fun mainKafkaStream() = setupKafkaStreams(kafkaStreamTopology(), "soknadsarkiverer-main")

	@Bean
	fun retryKafkaStream() = setupKafkaStreams(kafkaRetryTopology(), "soknadsarkiverer-retry")

	private fun setupKafkaStreams(topology: Topology, applicationId: String): KafkaStreams {
		val kafkaStreams = KafkaStreams(topology, kafkaConfig(applicationId))
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		return kafkaStreams
	}

	fun kafkaExceptionHandler(): KafkaExceptionHandler {
		val handler = KafkaExceptionHandler()
		handler.configure(kafkaConfig("soknadsarkiverer-exception").map { (k, v) -> k.toString() to v.toString() }.toMap())
		return handler
	}

	class WrapperProcessor : Transformer<String, ArchivalData, KeyValue<KafkaMsgWrapper.Value<KafkaMsg>, ArchivalData>> {
		private lateinit var context: ProcessorContext

		override fun init(context: ProcessorContext) {
			this.context = context
		}

		override fun transform(key: String, value: ArchivalData): KeyValue<KafkaMsgWrapper.Value<KafkaMsg>, ArchivalData> {
			val retryCount = getRetryCount()
			return KeyValue(KafkaMsgWrapper.Value(KafkaMsg(key, value, retryCount)), value)
		}

		private fun getRetryCount(): Int {
			return context.headers()
				.filter { it.key() == RETRY_COUNT_HEADER }
				.map { it.value() }
				.map { IntegerDeserializer().deserialize("", it) }
				.getOrElse(0) { 0 }
		}

		override fun close() {
		}
	}

	companion object {
		const val DEAD_LETTER_TOPIC = "dead-letter.topic"
		const val RETRY_TOPIC = "retry.topic"
		const val RETRY_COUNT_HEADER = "retry-count"
		const val KAFKA_MAX_RETRY_COUNT = "kafka-max-retry-count"
	}
}

data class KafkaMsg(val key: String, val value: ArchivalData, val retryCount: Int = 0) {
	override fun toString(): String {
		return "[KafkaMsg - key: '$key', retryCount: '$retryCount', value: '$value']"
	}
}

sealed class KafkaMsgWrapper<out T> {
	object None : KafkaMsgWrapper<Nothing>()
	data class Value<T>(val t: T) : KafkaMsgWrapper<T>()
}
