package no.nav.soknad.arkivering.soknadsarkiverer.config

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.soknad.arkivering.avroschemas.EventTypes.RECEIVED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.KafkaStreamsConfig.Companion.KAFKA_PUBLISHER
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.DeserializationExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

@Configuration
class KafkaStreamsConfig(private val appConfiguration: AppConfiguration,
												 private val schedulerService: SchedulerService,
												 private val kafkaPublisher: KafkaPublisher) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	fun streamsBuilder() = StreamsBuilder()

	@Bean
	fun handleStream(builder: StreamsBuilder): KStream<String, Soknadarkivschema> {

		val inputTopicStream = builder.stream(appConfiguration.kafkaConfig.inputTopic, Consumed.with(Serdes.String(), createAvroSerde()))
		val eventStream: KStream<String, ProcessingEvent> = inputTopicStream
			.peek { key, soknadarkivschema -> schedulerService.schedule(key, soknadarkivschema) }
			.mapValues { _, _ -> ProcessingEvent(RECEIVED) }

		eventStream.to(appConfiguration.kafkaConfig.processingTopic)
		return inputTopicStream
	}

	@Bean
	fun setupKafkaStreams(streamsBuilder: StreamsBuilder): KafkaStreams {
		val topology = streamsBuilder.build()

		val kafkaStreams = KafkaStreams(topology, kafkaConfig("soknadsarkiverer-main"))
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		logger.info("Ferdig setupKafkaStreams")
		return kafkaStreams
	}

	private fun kafkaConfig(applicationId: String) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[StreamsConfig.APPLICATION_ID_CONFIG] = applicationId
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
		if ("TRUE" == appConfiguration.kafkaConfig.secure) {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
			it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
			it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
		}
		it[KAFKA_PUBLISHER] = kafkaPublisher
	}

	private fun kafkaExceptionHandler() = KafkaExceptionHandler().also {
		it.configure(kafkaConfig("soknadsarkiverer-exception").map { (k, v) -> k.toString() to v }.toMap())
	}

	private fun createAvroSerde(): SpecificAvroSerde<Soknadarkivschema> {

		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		return SpecificAvroSerde<Soknadarkivschema>().also { it.configure(serdeConfig, false) }
	}

	companion object {
		const val KAFKA_PUBLISHER = "kafka.publisher"
	}
}

class KafkaExceptionHandler : Thread.UncaughtExceptionHandler, DeserializationExceptionHandler {
	private val logger = LoggerFactory.getLogger(javaClass)

	private lateinit var kafkaPublisher: KafkaPublisher


	override fun uncaughtException(t: Thread, e: Throwable) {

		val message = createMessage("Uncaught exception", e)
		logger.error(message)

		kafkaPublisher.putMessageOnTopic(null, message)
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
	}

	private fun getConfigForKey(configs: Map<String, *>, key: String): Any? {
		if (configs.containsKey(key)) {
			return configs[key]
		} else {
			val msg = "Could not find key '${key}' in configuration! Won't be able to create event on Message topic!"
			logger.error(msg)
			throw Exception(msg)
		}
	}
}


@Service
class KafkaPublisher(private val appConfiguration: AppConfiguration) {
	private val kafkaProcessingEventProducer = KafkaProducer<String, ProcessingEvent>(kafkaConfigMap())
	private val kafkaMessageProducer = KafkaProducer<String, String>(kafkaConfigMap())

	fun putProcessingEventOnTopic(key: String, value: ProcessingEvent, headers: Headers = RecordHeaders()): RecordMetadata {
		val topic = appConfiguration.kafkaConfig.processingTopic
		val kafkaProducer = kafkaProcessingEventProducer
		return putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putMessageOnTopic(key: String?, value: String, headers: Headers = RecordHeaders()): RecordMetadata {
		val topic = appConfiguration.kafkaConfig.messageTopic
		val kafkaProducer = kafkaMessageProducer
		return putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun <T> putDataOnTopic(key: String?, value: T, headers: Headers, topic: String,
																 kafkaProducer: KafkaProducer<String, T>): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.forEach { h -> producerRecord.headers().add(h) }

		return kafkaProducer
			.send(producerRecord)
			.get(1000, TimeUnit.MILLISECONDS) // Blocking call
	}

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
			if ("TRUE" == appConfiguration.kafkaConfig.secure) {
				it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
				it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
				it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
			}
		}
	}
}
