package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TopologyTestDriver
import org.mockito.ArgumentCaptor
import org.slf4j.LoggerFactory
import java.util.*

open class KafkaTopologyTestDriver {
	private val logger = LoggerFactory.getLogger(javaClass)

	private lateinit var testDriver: TopologyTestDriver

	private lateinit var inputTopic: TestInputTopic<String, Soknadarkivschema>
	private lateinit var inputTopicForBadData: TestInputTopic<String, String>
	private lateinit var processingEventTopic: TestInputTopic<String, ProcessingEvent>

	private fun setupKafkaTopologyTestDriver(
		appConfiguration: AppConfiguration,
		taskListService: TaskListService,
		kafkaPublisher: KafkaPublisher
	) {

		logger.info("**setupKafkaTopologyTestDriver**")
		val builder = StreamsBuilder()
		KafkaStreamsSetup(appConfiguration, taskListService, kafkaPublisher).kafkaStreams(builder)
		val topology = builder.build()

		// Dummy properties needed for test diver
		val props = Properties().also {
			it[StreamsConfig.APPLICATION_ID_CONFIG] = "test_" + UUID.randomUUID().toString()
			it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = "dummy:1234"
			it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
			it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
			it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
			it[SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
			it[KAFKA_PUBLISHER] = kafkaPublisher
			it[APP_CONFIGURATION] = appConfiguration
		}

		// Create test driver
		testDriver = TopologyTestDriver(topology, props)
		val schemaRegistry = MockSchemaRegistry.getClientForScope(schemaRegistryScope)

		// Create Serdes used for test record keys and values
		val stringSerde = Serdes.String()
		val avroSoknadarkivschemaSerde = SpecificAvroSerde<Soknadarkivschema>(schemaRegistry)
		val avroProcessingEventSerde = SpecificAvroSerde<ProcessingEvent>(schemaRegistry)

		// Configure Serdes to use the same mock schema registry URL
		val config = hashMapOf(SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		avroSoknadarkivschemaSerde.configure(config, false)
		avroProcessingEventSerde.configure(config, false)


		val stringSerializer = stringSerde.serializer()
		val soknadarkivschemaSerializer = avroSoknadarkivschemaSerde.serializer()
		val processingEventSerializer = avroProcessingEventSerde.serializer()
		val inputTopicName = appConfiguration.kafkaConfig.inputTopic
		val processingTopicName = appConfiguration.kafkaConfig.processingTopic

		// Define input and output topics to use in tests
		inputTopic = testDriver.createInputTopic(inputTopicName, stringSerializer, soknadarkivschemaSerializer)
		inputTopicForBadData = testDriver.createInputTopic(inputTopicName, stringSerializer, stringSerializer)
		processingEventTopic = testDriver.createInputTopic(processingTopicName, stringSerializer, processingEventSerializer)
	}

	fun closeTestDriver() {
		logger.info("**Closing testDriver**")
		testDriver.close()
	}


	fun putDataOnInputTopic(key: String, value: Soknadarkivschema) {
		inputTopic.pipeInput(key, value)
	}

	fun putBadDataOnInputTopic(key: String, value: String) {
		inputTopicForBadData.pipeInput(key, value)
	}

	fun putDataOnProcessingTopic(key: String, value: ProcessingEvent) {
		processingEventTopic.pipeInput(key, value)
	}


	inner class TopologyTestDriverBuilder {
		private var appConfiguration = createAppConfiguration()
		private var taskListService: TaskListService = mock()
		private var kafkaPublisher: KafkaPublisher = mock()

		fun withAppConfiguration(appConfiguration: AppConfiguration) = apply { this.appConfiguration = appConfiguration }
		fun withTaskListService(taskListService: TaskListService) = apply { this.taskListService = taskListService }
		fun withKafkaPublisher(kafkaPublisher: KafkaPublisher) = apply { this.kafkaPublisher = kafkaPublisher }

		@Suppress("unused")
		fun runScheduledTasksOnScheduling(scheduler: Scheduler): TopologyTestDriverBuilder {
			val captor = argumentCaptor<() -> Unit>()
			whenever(scheduler.schedule(capture(captor), any()))
				.then { captor.value.invoke() }
			return this
		}

		fun putProcessingEventLogsOnTopic(): TopologyTestDriverBuilder {
			EventTypes.values().forEach { putProcessingEventLogOnTopic(it) }
			return this
		}

		private fun putProcessingEventLogOnTopic(eventType: EventTypes) {
			val captor = argumentCaptor<String>()
			whenever(kafkaPublisher.putProcessingEventOnTopic(capture(captor), eq(ProcessingEvent(eventType)), any()))
				.then { processingEventTopic.pipeInput(captor.value, ProcessingEvent(eventType)) }
		}

		fun setup() {
			setupKafkaTopologyTestDriver(appConfiguration, taskListService, kafkaPublisher)
		}

		private inline fun <reified T> argumentCaptor(): ArgumentCaptor<T> = ArgumentCaptor.forClass(T::class.java)
	}

	fun setupKafkaTopologyTestDriver() = TopologyTestDriverBuilder()
}

fun createAppConfiguration() = AppConfiguration(AppConfiguration.KafkaConfig(
		inputTopic = "inputTopic",
		processingTopic = "processingTopic",
		schemaRegistryUrl = schemaRegistryUrl,
		servers = "bootstrapServers"))


const val schemaRegistryScope: String = "mocked-scope"
const val schemaRegistryUrl = "mock://$schemaRegistryScope"
