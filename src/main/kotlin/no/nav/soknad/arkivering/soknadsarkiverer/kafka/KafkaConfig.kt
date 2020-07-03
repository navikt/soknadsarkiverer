package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.dto.ProcessingEventDto
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Joined
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.state.KeyValueStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*

@Configuration
class KafkaConfig(private val appConfiguration: AppConfiguration,
									private val schedulerService: TaskListService,
									private val kafkaPublisher: KafkaPublisher) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val stringSerde = Serdes.StringSerde()
	private val intSerde = Serdes.IntegerSerde()
	private val soknadarkivschemaSerde = createSoknadarkivschemaSerde()
	private val processingEventSerde = createProcessingEventSerde()
	private val mutableListSerde: Serde<MutableList<String>> = MutableListSerde()

	@Bean
	fun streamsBuilder() = StreamsBuilder()

	@Bean
	fun recreationStream(streamsBuilder: StreamsBuilder): KStream<String, ProcessingEvent> {

		val joined = Joined.with(stringSerde, intSerde, soknadarkivschemaSerde, "SoknadsarkivCountJoined")

		val inputTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.inputTopic, Consumed.with(stringSerde, soknadarkivschemaSerde))

		val inputTable = inputTopicStream
			.peek { key, value -> logger.info("before toTable $key $value")}
			.toTable()

		inputTopicStream
			.peek { key, value -> logger.info("before publish $key $value")}
			.foreach { key, _ -> kafkaPublisher.putProcessingEventOnTopic(key, ProcessingEvent(EventTypes.RECEIVED))}

		val processingTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.processingTopic, Consumed.with(stringSerde, processingEventSerde))
		val counts = processingTopicStream
			.peek { key, value -> logger.info("ProcessingTopic - $key: $value") }
			.mapValues { processingEvent -> processingEvent.getType().name }
			.groupByKey()
			.aggregate(
				{ mutableListOf() },
				{ _, value, aggregate ->
					aggregate.add(value)
					aggregate
				},
				Materialized.`as`<String, MutableList<String>, KeyValueStore<Bytes, ByteArray>>("ProcessingEventDtos")
					.withValueSerde(mutableListSerde)
			)
			.mapValues { processingEvents -> ProcessingEventDto(processingEvents) }
			.mapValues { key, processingEventDto ->
				if (processingEventDto.isFinished()) {
					schedulerService.finishTask(key)
					null // Return null which acts as a tombstone, thus removing the entry from the table
				} else {
					processingEventDto.getNumberOfStarts()
				}
			}

		counts
			.toStream()
			.leftJoin(inputTable, { count, soknadarkivschema -> soknadarkivschema to (count ?: 0) }, joined)
			.peek { key, pair -> logger.info("Apa $key $pair") }
			.foreach { key, (soknadsarkivschema, count) -> schedulerService.addOrUpdateTask(key, soknadsarkivschema, count) }

		return processingTopicStream
	}

	@Bean
	fun setupRecreationStream(streamsBuilder: StreamsBuilder): KafkaStreams {
		val topology = streamsBuilder.build()

		val kafkaStreams = KafkaStreams(topology, kafkaConfig("soknadsarkiverer-recreation"))
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		return kafkaStreams
	}

	private fun kafkaConfig(applicationId: String) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[StreamsConfig.APPLICATION_ID_CONFIG] = applicationId
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = LogAndContinueExceptionHandler::class.java

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

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createSoknadarkivschemaSerde(): SpecificAvroSerde<Soknadarkivschema> = createAvroSerde()

	private fun <T: SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}

	companion object {
		const val KAFKA_PUBLISHER = "kafka.publisher"
	}
}
