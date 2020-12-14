package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.DeserializationExceptionHandler
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter


class KafkaExceptionHandler : Thread.UncaughtExceptionHandler, DeserializationExceptionHandler {
	private val logger = LoggerFactory.getLogger(javaClass)

	private lateinit var kafkaPublisher: KafkaPublisher
	private lateinit var kafkaStreamsInstance: KafkaStreams


	override fun uncaughtException(t: Thread, e: Throwable) {

		val message = createMessage("Uncaught exception", e)
		logger.error(message)

		kafkaPublisher.putMessageOnTopic(null, message)

		if (e is TopicAuthorizationException) {
			// Every once in a while, it seems that Kafka throws a TopicAuthorizationException. The remedy seems to be to
			// restart the kafka streams instance.
			logger.error("Got TopicAuthorizationException. Will attempt to restart the Kafka Streams Instance.")
			kafkaStreamsInstance.close()
			kafkaStreamsInstance.cleanUp()
			kafkaStreamsInstance.start()
			logger.info("Kafka Streams Instance restarted.")
		}
	}

	override fun handle(context: ProcessorContext, record: ConsumerRecord<ByteArray, ByteArray>, exception: Exception): DeserializationExceptionHandler.DeserializationHandlerResponse {

		val key = createKey(record)
		val message = createMessage("Exception when deserializing data", exception)
		logger.error("For key '$key': $message")

		kafkaPublisher.putMessageOnTopic(key, message)

		return DeserializationExceptionHandler.DeserializationHandlerResponse.CONTINUE
	}

	private fun createKey(record: ConsumerRecord<ByteArray, ByteArray>) =
		Serdes.String().deserializer().deserialize("topic", record.key())

	private fun createMessage(description: String, exception: Throwable): String {
		val sw = StringWriter()
		exception.printStackTrace(PrintWriter(sw))
		val stacktrace = sw.toString()
		return "$description: '${exception.message}'\n$stacktrace"
	}

	override fun configure(configs: Map<String, *>) {
		kafkaPublisher = getConfigForKey(configs, KAFKA_PUBLISHER) as KafkaPublisher
		kafkaStreamsInstance = getConfigForKey(configs, KAFKA_STREAMS_INSTANCE) as KafkaStreams
	}

	private fun getConfigForKey(configs: Map<String, *>, key: String): Any? {
		if (configs.containsKey(key)) {
			return configs[key]
		} else {
			val msg = "Could not find key '${key}' in configuration!"
			logger.error(msg)
			throw Exception(msg)
		}
	}
}
