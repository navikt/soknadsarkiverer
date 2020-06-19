package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.dto.ProcessingEventDto
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
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
									private val schedulerService: SchedulerService,
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

		val joined = Joined.with(stringSerde, soknadarkivschemaSerde, intSerde, "SoknadsarkivCountJoined")

		val inputTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.inputTopic, Consumed.with(stringSerde, soknadarkivschemaSerde))


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
			.mapValues { processingEventDto -> if (processingEventDto.isFinished()) -1 else processingEventDto.getNumberOfStarts() }

		inputTopicStream
			.peek { key, value -> logger.info("InputTopic - $key: $value") }
			.leftJoin(counts, { soknadarkivschema, count -> soknadarkivschema to count }, joined)
			.filter  { key, (soknadsarkivschema, count) -> shouldSchedule(count, soknadsarkivschema, key) }
			.mapValues { (soknadarkivschema, count) -> soknadarkivschema to (count ?: 0) }
			.peek    { key, (_, count) -> logger.info("For key '$key': Will schedule with count $count") }
			.foreach { key, (soknadsarkivschema, count) -> schedulerService.schedule(key, soknadsarkivschema!!, count) }

		return processingTopicStream
	}

	private fun shouldSchedule(count: Int?, soknadsarkivschema: Soknadarkivschema?, key: String?): Boolean {
		return when {
			count == null -> true // This case means that there are no previous ProcessingEvents.
			count < 0 -> false // This case means that the ProcessingEvents are finished.
			soknadsarkivschema == null -> { // Should not ever occur, but let's protect against NPE's anyway.
				logger.error("For key '$key': Found no associated Soknadsarkivschema on the input topic. Will ignore and continue.")
				false
			}
			else -> true
		}
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
