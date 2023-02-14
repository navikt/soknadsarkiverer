package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utility.LeaderElection
import no.nav.soknad.arkivering.soknadsarkiverer.utility.LeaderSelectionUtility
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SslConfigs
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
import java.util.*

class KafkaStreamsSetup(
	private val applicationState: ApplicationState,
	private val taskListService: TaskListService,
	private val kafkaPublisher: KafkaPublisher,
	private val kafkaConfig: KafkaConfig,
	private val topicSelection: TopicSelection
) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val stringSerde = Serdes.StringSerde()
	private val processingEventSerde = createProcessingEventSerde()
	private val soknadarkivschemaSerde = createSoknadarkivschemaSerde()
	private val mutableListSerde: Serde<MutableList<String>> = MutableListSerde()


	private fun kafkaStreams(streamsBuilder: StreamsBuilder) {
		val joinDef = Joined.with(stringSerde, processingEventSerde, soknadarkivschemaSerde, "archivingState")
		val materialized = Materialized.`as`<String, MutableList<String>, KeyValueStore<Bytes, ByteArray>>("processingeventdtos").withValueSerde(mutableListSerde)
		val mainTopicStream = streamsBuilder.stream(topicSelection.selectTopicVersion(TopicTypes.MAIN_TOPIC, kafkaConfig), Consumed.with(stringSerde, soknadarkivschemaSerde))
		val processingTopicStream = streamsBuilder.stream(topicSelection.selectTopicVersion(TopicTypes.PROCESSING_TOPIC, kafkaConfig), Consumed.with(stringSerde, processingEventSerde))

		val mainTopicTable = mainTopicStream.toTable()

		mainTopicStream
			.peek { key, value -> logger.info("$key: Processing MainTopic. BehandlingsId: ${value.behandlingsid}") }
			.foreach { key, _ -> kafkaPublisher.putProcessingEventOnTopic(key, ProcessingEvent(EventTypes.RECEIVED)) }

		processingTopicStream
			.peek { key, value -> logger.info("$key: ProcessingTopic - ${value.type}") }
			// Aggregere state slik at RECEIVED kan erstattes av alle etterfølgende states: STARTED, ARCHIVED, FAILED, FINISHED
			.mapValues { processingEvent -> processingEvent.type.name }
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
			.mapValues { processingEvents -> processingEvents.getNewestState() }
			.toStream()
			.peek { key, state -> logger.debug("$key: ProcessingTopic in state $state") }
			.filter { key, state -> !(isConsideredFinished(key, state)) }
			.leftJoin(mainTopicTable, { state, soknadarkivschema -> soknadarkivschema to state }, joinDef) // Oppdatere state på tabell, archivingState, ved join av soknadarkivschema og state.
			.filter { key, (soknadarkivschema, _) -> filterSoknadarkivschemaThatAreNull(key, soknadarkivschema) } // Ta bort alle innslag i tabell der soknadarkivschema er null.
			.peek { key, (soknadarkivschema, state) -> logger.debug("$key: ProcessingTopic will add/update task. State: $state Soknadarkivschema: ${soknadarkivschema.print()}") }
			.foreach { key, (soknadarkivschema, state) -> taskListService.addOrUpdateTask(key, soknadarkivschema, state.type) } // For hvert innslag i tabell (key, soknadarkivschema, count), skeduler arkveringstask
	}

	private fun isConsideredFinished(key: String, processingEvent: ProcessingEvent): Boolean {
		return when (processingEvent.type) {
			EventTypes.FINISHED -> {
				taskListService.finishTask(key)
				true
			}
			EventTypes.FAILURE -> {
				taskListService.failTask(key)
				true
			}
			else -> false
		}
	}


	private fun filterSoknadarkivschemaThatAreNull(key: String, soknadarkivschema: Soknadarkivschema?): Boolean {
		if (soknadarkivschema == null)
			logger.error("$key: Soknadarkivschema is null!")
		return soknadarkivschema != null
	}

	private fun Soknadarkivschema.print(): String {
		val fnr = "**fnr can be found in Soknadsmottaker's secure logs**"
		val a = Soknadarkivschema(this.behandlingsid, fnr, this.arkivtema, this.innsendtDato, this.soknadstype, this.mottatteDokumenter)
		return a.toString()
	}


	fun setupKafkaStreams(id: String): KafkaStreams {
		logger.info("Setting up KafkaStreams")

		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaStreams = KafkaStreams(topology, kafkaConfig(id))

		kafkaStreams.cleanUp()
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))

		logger.info("Finished setting up KafkaStreams")
		return kafkaStreams
	}

	private fun kafkaConfig(id: String) = Properties().also {
		it[SCHEMA_REGISTRY_URL_CONFIG] = kafkaConfig.schemaRegistry.url
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[StreamsConfig.APPLICATION_ID_CONFIG] = id
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = LogAndContinueExceptionHandler::class.java
		it[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG] = 1000
		if (kafkaConfig.security.enabled == "TRUE") {
			it[SchemaRegistryClientConfig.USER_INFO_CONFIG] = "${kafkaConfig.schemaRegistry.username}:${kafkaConfig.schemaRegistry.password}"
			it[SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE] = "USER_INFO"
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = kafkaConfig.security.protocol
			it[SslConfigs.SSL_KEYSTORE_TYPE_CONFIG] = "PKCS12"
			it[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] = kafkaConfig.security.trustStorePath
			it[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = kafkaConfig.security.trustStorePassword
			it[SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG] = kafkaConfig.security.keyStorePath
			it[SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
			it[SslConfigs.SSL_KEY_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
		}

		it[KAFKA_PUBLISHER] = kafkaPublisher
	}

	private fun kafkaExceptionHandler() = KafkaExceptionHandler().also {
		it.configure(
			kafkaConfig("soknadsarkiverer-exception")
				.also { config ->
					config[APP_STATE] = applicationState
					config[StreamsConfig.APPLICATION_ID_CONFIG] = "soknadsarkiverer-exception"
				}
				.map { (k, v) -> k.toString() to v }.toMap()
		)
	}

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createSoknadarkivschemaSerde(): SpecificAvroSerde<Soknadarkivschema> = createAvroSerde()

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(
			SCHEMA_REGISTRY_URL_CONFIG to kafkaConfig.schemaRegistry.url,
			SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE to "USER_INFO",
			SchemaRegistryClientConfig.USER_INFO_CONFIG to "${kafkaConfig.schemaRegistry.username}:${kafkaConfig.schemaRegistry.password}"
		)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}
}

const val APP_STATE = "app_state"
const val KAFKA_PUBLISHER = "kafka.publisher"
const val MESSAGE_ID = "MESSAGE_ID"
