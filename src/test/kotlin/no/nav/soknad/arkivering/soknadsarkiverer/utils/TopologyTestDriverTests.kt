package no.nav.soknad.arkivering.soknadsarkiverer.utils

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaExceptionHandler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TopologyTestDriver
import java.util.*

open class TopologyTestDriverTests {

	private lateinit var testDriver: TopologyTestDriver

	lateinit var inputTopic: TestInputTopic<String, Soknadarkivschema>
	lateinit var inputTopicForBadData: TestInputTopic<String, String>
	lateinit var processingEventTopic: TestInputTopic<String, ProcessingEvent>

	fun setupKafkaTopologyTestDriver(appConfiguration: AppConfiguration, schedulerService: SchedulerService, kafkaPublisher: KafkaPublisher) {
		val builder = StreamsBuilder()
		KafkaConfig(appConfiguration, schedulerService, kafkaPublisher).recreationStream(builder)
		val topology = builder.build()

		// Dummy properties needed for test diver
		val props = Properties().also {
			it[StreamsConfig.APPLICATION_ID_CONFIG] = "test"
			it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = "dummy:1234"
			it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
			it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
			it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
			it[KafkaConfig.KAFKA_PUBLISHER] = kafkaPublisher
		}

		// Create test driver
		testDriver = TopologyTestDriver(topology, props)
		val schemaRegistry = MockSchemaRegistry.getClientForScope(schemaRegistryScope)

		// Create Serdes used for test record keys and values
		val stringSerde = Serdes.String()
		val avroSoknadarkivschemaSerde = SpecificAvroSerde<Soknadarkivschema>(schemaRegistry)
		val avroProcessingEventSerde = SpecificAvroSerde<ProcessingEvent>(schemaRegistry)

		// Configure Serdes to use the same mock schema registry URL
		val config = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		avroSoknadarkivschemaSerde.configure(config, false)
		avroProcessingEventSerde.configure(config, false)

		// Define input and output topics to use in tests
		inputTopic = testDriver.createInputTopic(appConfiguration.kafkaConfig.inputTopic, stringSerde.serializer(), avroSoknadarkivschemaSerde.serializer())
		inputTopicForBadData = testDriver.createInputTopic(appConfiguration.kafkaConfig.inputTopic, stringSerde.serializer(), stringSerde.serializer())
		processingEventTopic = testDriver.createInputTopic(appConfiguration.kafkaConfig.processingTopic, stringSerde.serializer(), avroProcessingEventSerde.serializer())
	}

	fun closeTestDriver() {
		testDriver.close()
	}
}

fun createAppConfiguration() : AppConfiguration {
	val kafkaConfig = AppConfiguration.KafkaConfig(
		inputTopic = "inputTopic",
		processingTopic = "processingTopic",
		schemaRegistryUrl = schemaRegistryUrl,
		servers = "bootstrapServers")
	return AppConfiguration(kafkaConfig)
}

const val schemaRegistryScope: String = "mocked-scope"
const val schemaRegistryUrl = "mock://$schemaRegistryScope"
