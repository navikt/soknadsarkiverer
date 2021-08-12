package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.dto.ProcessingEventDto
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping.KafkaBootstrapConsumer
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
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
class KafkaConfig(
	private val appConfiguration: AppConfiguration,
	private val schedulerService: TaskListService,
	private val kafkaPublisher: KafkaPublisher,
	private val metrics: ArchivingMetrics
) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val stringSerde = Serdes.StringSerde()
	private val processingEventSerde = createProcessingEventSerde()
	private val soknadarkivschemaSerde = createSoknadarkivschemaSerde()
	private val mutableListSerde: Serde<MutableList<String>> = MutableListSerde()


	fun kafkaStreams(streamsBuilder: StreamsBuilder) {
		val joinDef = Joined.with(stringSerde, processingEventSerde, soknadarkivschemaSerde, "archivingState")
		val materialized = Materialized.`as`<String, MutableList<String>, KeyValueStore<Bytes, ByteArray>>("ProcessingEventDtos").withValueSerde(mutableListSerde)
		val inputTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.inputTopic, Consumed.with(stringSerde, soknadarkivschemaSerde))
		val processingTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.processingTopic, Consumed.with(stringSerde, processingEventSerde))

		val inputTable = inputTopicStream.toTable()

		inputTopicStream
			.peek { key, value -> logger.info("$key: Processing InputTopic. BehandlingsId: ${value.behandlingsid}") }
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
			.peek { key, state -> logger.debug("$key: ProcessingTopic filter with state $state") }
			.mapValues { key, state -> failureRepair(key, state) }
			.filter { key, state -> !(isConsideredFinished(key, state)) }
			.leftJoin(inputTable, { state, soknadarkivschema -> soknadarkivschema to state }, joinDef) // Oppdatere state på tabell, archivingState, ved join av soknadarkivschema og state.
			.filter { key, (soknadsarkivschema, _) -> filterSoknadsarkivschemaThatAreNull(key, soknadsarkivschema) } // Ta bort alle innslag i tabell der soknadarkivschema er null.
			.peek { key, (soknadsarkivschema, state) -> logger.debug("$key: ProcessingTopic scehdule job in state $state - ${soknadsarkivschema.print()}") }
			.foreach { key, (soknadsarkivschema, state) -> schedulerService.addOrUpdateTask(key, soknadsarkivschema, state.type) } // For hvert innslag i tabell (key, soknadarkivschema, count), skeduler arkveringstask
	}

	private fun failureRepair(key: String, state: ProcessingEvent): ProcessingEvent {
		return if (key in listOf("e0f6306d-aea7-4486-90cc-459273d2fa19", "020bf3b1-5448-4d69-89fb-73f88dd24d5b", "47305acd-cd0d-41c2-aa11-4037469bb084", "a531921e-3c6a-40fb-b9bf-a4888eb67848", "1bb4bca0-2317-49ad-baf8-9ab5d863ea02", "f7f06082-8909-4a0f-9bdb-83571acda121", "62c1f9d7-1c8d-454d-888e-2b77a49976ec", "09ed248f-a440-425a-a923-a8e54982e26d", "db0951df-321e-4ed4-90d9-185dbfef109f", "a63f8ffe-320d-43eb-a3b7-3e5f95dd93ed", "d43aeb95-054e-4cd7-9e45-87fbff362575", "70872107-a93e-432e-839c-90526453f41b", "af11b2d4-469a-45f2-a6e3-006378ae267a", "5895359f-8c8b-44c8-9491-3a417f6fc540", "470647dd-a69d-48f6-ba04-dd7b78ee0f36", "eefbccf7-cdb8-4585-9b68-04dad03eb20b", "79cacca3-9584-4374-a1d1-1e3b19ea5d39", "7bba4805-17e7-436a-a8fd-8e4723c57e23", "2f57cf87-24b3-4675-bd30-bba2513be608", "4f045ad9-1471-4069-93fa-1297a5a9b5ce", "0b633999-dce4-4260-8526-33b6fda6c8ef", "0ea3ad07-3e6c-4c28-b9a3-7dc97189ba4d", "7b4ecfff-0081-4375-8660-4000b282edc7", "bd66bf4d-4945-4b24-8054-630374440e76", "2df08893-027b-406d-a27d-d5453130f951", "969e6dab-115f-40c5-955c-612f7150d28c", "094c8940-f21d-461f-9156-7bac8bfca3a9", "99754b2e-0ff9-4494-a1a1-7760a721da94", "34fd60c0-b91c-4aa7-970e-790691d35eb5", "eed60e85-360b-4b15-a208-3da049db3bdd", "da96e785-f8d1-47a9-a11e-6f9946eb66b3", "fbceff08-bbe0-452d-9ee1-ce552d5da5e2", "30c2591d-cc94-4a3f-be4f-146931b79697", "778ef21b-6d4c-4a67-9bf3-5a4768cf7042", "83ec949f-b7d3-491b-92e6-3dc20afe7e7d", "7e1918ff-1264-4e06-aa8e-02da788a0a66", "63fb8c8e-05bc-42ba-9359-b81f41d166b4", "19ccf4a0-c113-4287-836a-e666c19b7c07", "40bf5e3d-ef5f-4431-a004-4debca456d53", "850cb005-7fd4-4a52-b874-0362f8359627", "15d1ead7-f90b-4790-be3c-e8c538217f9e", "fa1ad7b8-0291-48dd-8074-f9af29be0099", "6211de6c-5367-4af1-8550-de96bdb6fb16", "16ec56c5-6c58-420f-b63c-f279b27c7b40", "0dfd6e10-cbc2-4a92-84d6-6bca7b10dd9f", "c1916a69-8adf-4f16-92c1-b508f2d4241f", "83e8f083-b7d3-44a8-a294-e71f7332e8b9", "2e248d31-a446-4ca0-917a-d78f76d6fbc9", "2aefc159-c396-4d5b-b703-0f008f1619ef", "6afd4cd4-7653-4740-b1e2-9518edeccee3", "61024294-eacf-482e-a802-48c2838dc4b5", "368cb078-be4c-4c88-8d38-2b0c663bc500", "1e737872-eb32-4d10-a7cf-a4868c8e0320", "42977ebd-33cf-4f5c-94fb-5d3dbcdc3449", "5e162096-47d7-4822-a733-0038a63b50f4", "6f5e1acf-ff41-447e-85d9-15c37cea2d56", "67a6f383-d5dd-4b5a-8136-b5a384fb2f36", "809059ab-49a6-4a62-a2a4-adf18ac1c980")) {
			logger.info("$key: Overriding event type; changing status from $state to STARTED.")
			ProcessingEvent(EventTypes.STARTED)
		} else
			state
	}

	private fun isConsideredFinished(key: String, processingEvent: ProcessingEvent): Boolean {
		return when (processingEvent.type) {
			EventTypes.FINISHED -> {
				schedulerService.finishTask(key)
				true
			}
			EventTypes.FAILURE -> {
				schedulerService.failTask(key)
				true
			}
			else -> false
		}
	}


	private fun filterSoknadsarkivschemaThatAreNull(key: String, soknadsarkiveschema: Soknadarkivschema?): Boolean {
		if (soknadsarkiveschema == null)
			logger.error("$key: Soknadsarkivschema is null!")
		return soknadsarkiveschema != null
	}

	private fun Soknadarkivschema.print(): String {
		val fnr = "" // Do not print fnr to log
		val a = Soknadarkivschema(this.behandlingsid, fnr, this.arkivtema, this.innsendtDato, this.soknadstype, this.mottatteDokumenter)
		return a.toString()
	}


	@Bean
	fun setupKafkaStreams(): KafkaStreams {
		metrics.setUpOrDown(1.0)
		logger.info("Starting Kafka Bootstrap Consumer to create tasks for any tasks that had not yet been finished")
		KafkaBootstrapConsumer(appConfiguration, schedulerService).recreateState()
		logger.info("Finished Kafka Bootstrap Consumer")

		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaStreams = KafkaStreams(topology, kafkaConfig(appConfiguration.kafkaConfig.groupId))

		kafkaStreams.cleanUp()
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))

		appConfiguration.state.started = true
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
