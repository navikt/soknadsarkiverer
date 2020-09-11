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

	private val intSerde = Serdes.IntegerSerde()
	private val stringSerde = Serdes.StringSerde()
	private val processingEventSerde = createProcessingEventSerde()
	private val soknadarkivschemaSerde = createSoknadarkivschemaSerde()
	private val mutableListSerde: Serde<MutableList<String>> = MutableListSerde()

	fun kafkaStreams(streamsBuilder: StreamsBuilder) {

		val joined = Joined.with(stringSerde, intSerde, soknadarkivschemaSerde, "SoknadsarkivCountJoined")
		val materialized = Materialized.`as`<String, MutableList<String>, KeyValueStore<Bytes, ByteArray>>("ProcessingEventDtos").withValueSerde(mutableListSerde)
		val inputTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.inputTopic, Consumed.with(stringSerde, soknadarkivschemaSerde))
		val processingTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.processingTopic, Consumed.with(stringSerde, processingEventSerde))


		val inputTable = inputTopicStream.toTable()

		inputTopicStream
			.foreach { key, _ -> kafkaPublisher.putProcessingEventOnTopic(key, ProcessingEvent(EventTypes.RECEIVED)) }

		processingTopicStream
			.peek { key, value -> logger.info("$key: ProcessingTopic - $value") }
			.mapValues { processingEvent -> processingEvent.getType().name }
			.groupByKey()
			.aggregate(
				{ mutableListOf() },
				{ _, value, aggregate ->
					aggregate.add(value)
					aggregate
				},
				materialized
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
			.toStream()
			.peek { key, count -> logger.info("$key: Processing Events - $count") }
			.leftJoin(inputTable, { count, soknadarkivschema -> soknadarkivschema to (count ?: 0) }, joined)
			.filter { key, (soknadsarkiveschema, _) -> filterSoknadsarkivschemaThatAreNull(key, soknadsarkiveschema) }
			.peek { key, pair -> logger.info("$key: About to schedule - $pair") }
			.foreach { key, (soknadsarkivschema, count) -> schedulerService.addOrUpdateTask(key, soknadsarkivschema, count) }
	}

	private fun filterSoknadsarkivschemaThatAreNull(key: String, soknadsarkiveschema: Soknadarkivschema?): Boolean {
		logger.error("$key: Soknadsarkivschema is null!")
		return soknadsarkiveschema != null
	}

	@Bean
	fun setupKafkaStreams(): KafkaStreams {
		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaStreams = KafkaStreams(topology, kafkaConfig("soknadsarkiverer-streams-${UUID.randomUUID()}"))
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
		it[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG] = 1000

		if (appConfiguration.kafkaConfig.secure == "TRUE") {
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

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}

	companion object {
		const val KAFKA_PUBLISHER = "kafka.publisher"
	}
}
