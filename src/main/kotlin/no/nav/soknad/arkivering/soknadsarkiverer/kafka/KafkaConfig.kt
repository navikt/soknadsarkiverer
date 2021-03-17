package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.ArchivingStateSchema
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.dto.ProcessingEventDto
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.converter.createProcessEvent
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
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
									private val kafkaPublisher: KafkaPublisher,
									private val metrics: ArchivingMetrics) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val stringSerde = Serdes.StringSerde()
	private val processingEventSerde = createProcessingEventSerde()
	private val soknadarkivschemaSerde = createSoknadarkivschemaSerde()
	private val mutableListSerde: Serde<MutableList<String>> = MutableListSerde()


	fun modifiedKafkaStreams(streamsBuilder: StreamsBuilder) {
		val joinDef = Joined.with(stringSerde, processingEventSerde, soknadarkivschemaSerde, "archivingState")
		val materialized = Materialized.`as`<String, MutableList<String>, KeyValueStore<Bytes, ByteArray>>("ProcessingEventDtos").withValueSerde(mutableListSerde)
		val inputTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.inputTopic, Consumed.with(stringSerde, soknadarkivschemaSerde))
		val processingTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.processingTopic, Consumed.with(stringSerde, processingEventSerde))

		val inputTable = inputTopicStream.toTable()

		inputTopicStream
			.peek { key, value -> logger.info("$key: Processing InputTopic - $value") }
			.foreach { key, e -> kafkaPublisher.putProcessingEventOnTopic(key, createProcessEvent(EventTypes.RECEIVED)) }

		processingTopicStream
			.peek { key, value -> logger.info("$key: ProcessingTopic - ${value.type}") }
		// Aggregere state slik at RECEIVED kan erstattes av alle etterfølgende states: STARTED, ARCHIVED, FAILED, FINISHED
			.mapValues { processingEvent -> processingEvent.getType().name }
			.groupByKey()
			.aggregate(  // Returnerer en tabell <Key, Value> der value er en Liste av eventtype
				{ mutableListOf() }, // Initiator
				{ _, value, aggregate ->  // aggregator
					aggregate.add(value)
					aggregate
				},
				materialized // Materialisert som KeyValue store
			)
			.mapValues { processingEvents -> ProcessingEventDto(processingEvents) } // mapper eventtype til ProcessingEventDto
			.mapValues { processingEvents ->  processingEvents.getNewestState()}
			.toStream()
			.peek{ key, state -> logger.debug("$key: ProcessingTopic filter with state $state")}
			.filter {key, state -> !(erFerdig(key, state) ) }
			.leftJoin(inputTable, { state, soknadarkivschema ->  soknadarkivschema to state }, joinDef) // Oppdatere state på tabell, archivingState, ved join av soknadarkivschema og state.
			.filter { key, (soknadsarkiveschema, _) -> filterSoknadsarkivschemaThatAreNull(key, soknadsarkiveschema) } // Ta bort alle innslag i tabell der soknadarkivschema er null.
			.peek { key, (soknadsarkivschema, state) -> logger.debug("$key: ProcessingTopic scehdule job in state $state - ${soknadsarkivschema.print()}") }
			.foreach { key, (soknadsarkivschema, state) -> schedulerService.addOrUpdateTask(key, soknadsarkivschema, state.type) } // For hvert innslag i tabell (key, soknadarkivschema, count), skeduler arkveringstask

	}

	private fun erFerdig(key: String, processingEvent: ProcessingEvent): Boolean {
		if (processingEvent.type == EventTypes.FINISHED) {
			schedulerService.finishTask(key)
			return true
		} else if (processingEvent.type == EventTypes.FAILURE) {
			schedulerService.failTask(key)
			return true
		} else return false
	}


	private fun filterSoknadsarkivschemaThatAreNull(key: String, soknadsarkiveschema: Soknadarkivschema?): Boolean {
		if (soknadsarkiveschema == null)
			logger.error("$key: Soknadsarkivschema is null!")
		return soknadsarkiveschema != null
	}

	private fun Soknadarkivschema.print(): String {
		val fnr = "" // Do not print fnr to log
		val a = Soknadarkivschema(this.getBehandlingsid(), fnr, this.getArkivtema(), this.getInnsendtDato(), this.getSoknadstype(), this.getMottatteDokumenter())
		return a.toString()
	}


	@Bean
	fun setupKafkaStreams(): KafkaStreams {
		metrics.setUpOrDown(1.0)
		val streamsBuilder = StreamsBuilder()
		modifiedKafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaStreams = KafkaStreams(topology, kafkaConfig(appConfiguration.kafkaConfig.groupId+"cursor-reset")) // TODO remove '+"cursor-reset"'

		logger.info("SetupKafkaStreams: cleanUp kafka streams")
		kafkaStreams.cleanUp()
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		return kafkaStreams
	}

	private fun kafkaConfig(applicationId: String) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
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
		it.configure(
			kafkaConfig("soknadsarkiverer-exception")
				.also { config -> config[APP_CONFIGURATION] = appConfiguration }
				.map { (k, v) -> k.toString() to v }.toMap()
		)
	}

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createSoknadarkivschemaSerde(): SpecificAvroSerde<Soknadarkivschema> = createAvroSerde()

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}
}

const val APP_CONFIGURATION = "app_configuration"
const val KAFKA_PUBLISHER = "kafka.publisher"
const val MESSAGE_ID = "MESSAGE_ID"
