package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.soknadsarkiverer.config.KafkaConsumerConfig.Companion.RETRY_COUNT_HEADER
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.IntegerSerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.DeserializationExceptionHandler
import org.apache.kafka.streams.errors.DeserializationExceptionHandler.DeserializationHandlerResponse
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

class KafkaExceptionHandler : Thread.UncaughtExceptionHandler, DeserializationExceptionHandler {
	private lateinit var retryTopic: String
	private lateinit var deadLetterTopic: String
	private lateinit var bootstrapServer: String
	private var kafkaMaxRetryCount: Int = 0

	private val logger = LoggerFactory.getLogger(javaClass)


	private fun putDataOnTopic(topic: String, key: ByteArray, value: ByteArray, headers: Headers): RecordMetadata {
		val producerRecord = ProducerRecord(topic, key, value)
		headers.forEach { h -> producerRecord.headers().add(h) }

		return kafkaProducer().use {
			it.send(producerRecord).get(1000, TimeUnit.MILLISECONDS) // Blocking call
		}
	}

	override fun handle(context: ProcessorContext, record: ConsumerRecord<ByteArray, ByteArray>, exception: Exception): DeserializationHandlerResponse {
		logger.error("Exception when deserializing Kafka message", exception)

		try {
			putDataOnTopic(deadLetterTopic, record.key(), record.value(), RecordHeaders())
			logger.info("Put message on DLQ")

		} catch (e: Exception) {
			logger.error("Exception when trying to message that could not be deserialised to topic '$deadLetterTopic'", exception)
			return DeserializationHandlerResponse.FAIL
		}

		return DeserializationHandlerResponse.CONTINUE
	}

	fun retry(event: KafkaMsg) {
		val key = Serdes.String().serializer().serialize(retryTopic, event.key)
		val value = SoknadMottattDtoSerde().serializer().serialize(retryTopic, event.value)

		val retryCount = event.retryCount + 1
		val headers = RecordHeaders().add(RETRY_COUNT_HEADER, IntegerSerializer().serialize("", retryCount))

		val topic = if (retryCount < kafkaMaxRetryCount) retryTopic else deadLetterTopic

		putDataOnTopic(topic, key, value, headers)
		logger.info("Sent message to retry topic $topic")
	}

	override fun uncaughtException(t: Thread, e: Throwable) {
		logger.error("Uncaught exception", e)
	}

	override fun configure(configs: Map<String, *>) {
		retryTopic = getConfigForKey(configs, KafkaConsumerConfig.RETRY_TOPIC).toString()
		deadLetterTopic = getConfigForKey(configs, KafkaConsumerConfig.DEAD_LETTER_TOPIC).toString()
		bootstrapServer = getConfigForKey(configs, StreamsConfig.BOOTSTRAP_SERVERS_CONFIG).toString()
		kafkaMaxRetryCount = Integer.parseInt(getConfigForKey(configs, KafkaConsumerConfig.KAFKA_MAX_RETRY_COUNT).toString())
	}

	private fun getConfigForKey(configs: Map<String, *>, key: String): Any? {
		if (configs.containsKey(key)) {
			return configs[key]
		} else {
			val msg = "Could not find key '${key}' in configuration! Won't be able to put event on DLQ/retry topic"
			logger.error(msg)
			throw Exception(msg)
		}
	}


	private fun kafkaProducer() = KafkaProducer<ByteArray, ByteArray>(kafkaConfigMap())

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServer
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
		}
	}
}
