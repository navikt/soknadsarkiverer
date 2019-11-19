package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FileStorageRetrievingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.kafka.annotation.EnableKafkaStreams
import java.util.*

@EnableKafkaStreams
@Configuration
class KafkaConsumerConfig(val applicationProperties: ApplicationProperties,
													val fileStorageRetrievingService: FileStorageRetrievingService,
													val messageConverter: MessageConverter,
													val joarkArchiver: JoarkArchiver) {

	fun kafkaConfig() = Properties().also {
		it[RETRY_TOPIC] = applicationProperties.kafkaRetryTopic
		it[DEAD_LETTER_TOPIC] = applicationProperties.kafkaDeadLetterTopic
		it[StreamsConfig.APPLICATION_ID_CONFIG] = "soknadsarkiverer"
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = applicationProperties.kafkaBootstrapServers
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
	}

	private fun setupTopology(kstream: KStream<String, ArchivalData>) {

		kstream
			.map { key, archivalData           -> KeyValue(KafkaMsg(key, archivalData), archivalData) }
			.map { orig, archivalData          -> w(orig) { archivalData to fileStorageRetrievingService.getFilesFromFileStorage(archivalData) } }
			.map { orig, (archivalData, files) -> w(orig) { messageConverter.createJoarkData(archivalData, files) } }
			.foreach { orig, joarkData         -> w(orig) { joarkArchiver.putDataInJoark(joarkData) } }
	}

	/**
	 * This wrapper will execute the given lambda. If anything goes wrong and we get an exception,
	 * the Kafka record will be put into the exception, so that the exception handler can access the
	 * record and put it on the retry topic.
	 */
	private fun <T> w(originalMessage: KafkaMsg, function: () -> T): KeyValue<KafkaMsg, T> {
		try {
			return KeyValue(originalMessage, function.invoke())
		} catch (e: Exception) {
			throw SoknadsArkivererException(originalMessage, e)
		}
	}

	@Bean("mainTopology")
	fun kafkaStreamTopology(streamsBuilder: StreamsBuilder): KStream<String, ArchivalData> {
		val topic = applicationProperties.kafkaTopic
		val kStream = streamsBuilder.stream<String, ArchivalData>(topic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
		kStream.peek { key, value -> println("Received main event with key='$key' and value='$value'") }
		setupTopology(kStream)
		return kStream
	}

	@Bean("retryTopology")
	fun kafkaRetryTopology(streamsBuilder: StreamsBuilder): KStream<String, ArchivalData> {
		val topic = applicationProperties.kafkaRetryTopic
		val kStream = streamsBuilder.stream<String, ArchivalData>(topic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
		kStream.peek { key, value -> println("Received retry event with key='$key' and value='$value'") }
		// TODO: Implement backoff strategy
		setupTopology(kStream)
		return kStream
	}

	@Bean
	@DependsOn(value = ["mainTopology", "retryTopology"])
	fun kStream(streamsBuilder: StreamsBuilder, kafkaExceptionHandler: KafkaExceptionHandler): KafkaStreams {
		val topology = streamsBuilder.build()
		val kafkaStreams = KafkaStreams(topology, kafkaConfig())
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler)
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		return kafkaStreams
	}

	@Bean
	fun kafkaExceptionHandler(): KafkaExceptionHandler {
		val handler = KafkaExceptionHandler()
		handler.configure(kafkaConfig().map { (k, v) -> k.toString() to v.toString() }.toMap())
		return handler
	}

	companion object {
		const val DEAD_LETTER_TOPIC = "dead-letter.topic"
		const val RETRY_TOPIC = "retry.topic"
	}
}

data class KafkaMsg(val key: String, val value: ArchivalData)
