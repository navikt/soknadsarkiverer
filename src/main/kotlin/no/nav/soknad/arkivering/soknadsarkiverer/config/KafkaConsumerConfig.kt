package no.nav.soknad.arkivering.soknadsarkiverer.config

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FilestorageService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.apache.kafka.common.serialization.IntegerDeserializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Transformer
import org.apache.kafka.streams.kstream.TransformerSupplier
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*
import java.util.concurrent.TimeUnit

@Configuration
class KafkaConsumerConfig(val applicationProperties: ApplicationProperties,
													val filestorageService: FilestorageService,
													val messageConverter: MessageConverter,
													val joarkArchiver: JoarkArchiver) {

	private val logger = LoggerFactory.getLogger(javaClass)

	fun kafkaConfig(applicationId: String) = Properties().also {
		it[RETRY_TOPIC] = applicationProperties.kafkaRetryTopic
		it[DEAD_LETTER_TOPIC] = applicationProperties.kafkaDeadLetterTopic
		it[KAFKA_MAX_RETRY_COUNT] = applicationProperties.kafkaMaxRetryCount
		it[SCHEMA_REGISTRY_URL] = applicationProperties.schemaRegistryUrl
		it[StreamsConfig.APPLICATION_ID_CONFIG] = applicationId
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = applicationProperties.kafkaBootstrapServers
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
	}

	private fun setupTopology(kstream: KStream<KafkaMsgWrapper.Value<KafkaMsg>, Soknadarkivschema>) {

		kstream
			.map { msg, requestData  -> w(msg) { requestData to filestorageService.getFilesFromFilestorage(requestData) } }
			.map { msg, dataAndFiles -> w(msg) { messageConverter.createJoarkData(dataAndFiles?.first!!, dataAndFiles.second) } }
			.map { msg, joarkData    -> w(msg) { joarkArchiver.putDataInJoark(joarkData!!) } }
			.foreach { msg, _        -> w(msg) { filestorageService.deleteFilesFromFilestorage(getOriginalMessage(msg)) } }
	}

	private fun getOriginalMessage(wrappedMessage: KafkaMsgWrapper<KafkaMsg>?): Soknadarkivschema? {
		return if (wrappedMessage is KafkaMsgWrapper.Value) {
			val (orig) = wrappedMessage
			orig.value
		} else {
			null
		}
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
		val schemaRegistryUrl = applicationProperties.schemaRegistryUrl

		val streamsBuilder = StreamsBuilder()
		val kStream = streamsBuilder.stream(topic, Consumed.with(Serdes.String(), createAvroSerde(schemaRegistryUrl)))
			.transform(TransformerSupplier { WrapperTransformer() })
			.peek { kafkaMsg, _ -> logger.info("Received main event: ${kafkaMsg.t}") }

		setupTopology(kStream)
		return streamsBuilder.build()
	}

	fun kafkaRetryTopology(): Topology {
		val topic = applicationProperties.kafkaRetryTopic
		val schemaRegistryUrl = applicationProperties.schemaRegistryUrl

		val streamsBuilder = StreamsBuilder()
		val kStream = streamsBuilder.stream(topic, Consumed.with(Serdes.String(), createAvroSerde(schemaRegistryUrl)))
			.transform(TransformerSupplier { WrapperTransformer() })
			.peek { kafkaMsg, _ -> logger.info("Received retry event: ${kafkaMsg.t}") }
			.peek { kafkaMsg, _ -> backoff(kafkaMsg) }

		setupTopology(kStream)
		return streamsBuilder.build()
	}

	private fun backoff(kafkaMsg: KafkaMsgWrapper.Value<KafkaMsg>) {
		val retryCount = kafkaMsg.t.retryCount - 1
		val sleepTimes = applicationProperties.kafkaRetrySleepTime
		val waitTime = if (retryCount >= sleepTimes.size) sleepTimes.last() else sleepTimes[retryCount]

		logger.info("Will wait for $waitTime seconds before processing retry event")
		TimeUnit.SECONDS.sleep(waitTime.toLong())
		logger.info("Will now begin to process retry event")
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

	fun kafkaExceptionHandler() = KafkaExceptionHandler().also {
		it.configure(kafkaConfig("soknadsarkiverer-exception").map { (k, v) -> k.toString() to v.toString() }.toMap())
	}

	class WrapperTransformer : Transformer<String, Soknadarkivschema, KeyValue<KafkaMsgWrapper.Value<KafkaMsg>, Soknadarkivschema>> {

		private lateinit var context: ProcessorContext

		override fun init(context: ProcessorContext) {
			this.context = context
		}

		override fun transform(key: String, value: Soknadarkivschema): KeyValue<KafkaMsgWrapper.Value<KafkaMsg>, Soknadarkivschema> {
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
		const val SCHEMA_REGISTRY_URL = AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
	}
}

data class KafkaMsg(val key: String, val value: Soknadarkivschema, val retryCount: Int = 0) {
	override fun toString(): String {
		return "[KafkaMsg - key: '$key', retryCount: '$retryCount', value: '$value']"
	}
}

sealed class KafkaMsgWrapper<out T> {
	object None : KafkaMsgWrapper<Nothing>()
	data class Value<T>(val t: T) : KafkaMsgWrapper<T>()
}

fun createAvroSerde(schemaRegistryUrl: String): SpecificAvroSerde<Soknadarkivschema> {

	val serdeConfig = hashMapOf(KafkaConsumerConfig.SCHEMA_REGISTRY_URL to schemaRegistryUrl)
	return SpecificAvroSerde<Soknadarkivschema>().also { it.configure(serdeConfig, false) }
}
