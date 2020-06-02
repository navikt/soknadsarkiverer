package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattDokumentBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattVariantBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.SoknadarkivschemaBuilder
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.Serdes.StringSerde
import org.apache.kafka.streams.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.springframework.test.context.ActiveProfiles
import java.util.*

@ActiveProfiles("test")
class StateRecreatorTests {

	private val schemaRegistryScope: String = "mocked-scope"
	private val schemaRegistryUrl = "mock://$schemaRegistryScope"

	private val appConfiguration = createAppConfiguration()
	private lateinit var schedulerService: SchedulerService

	private lateinit var testDriver: TopologyTestDriver
	private lateinit var inputTopic: TestInputTopic<String, Soknadarkivschema>
	private lateinit var processingEventTopic: TestInputTopic<String, ProcessingEvent>

	private val soknadarkivschema = createRequestData()

	@BeforeEach
	fun setup() {
		schedulerService = mock()
		setupKafkaTopologyTestDriver()
	}

	private fun setupKafkaTopologyTestDriver() {
		val builder = StreamsBuilder()
		StateRecreator(appConfiguration, schedulerService, mock()).recreationStream(builder)
		val topology = builder.build()

		// Dummy properties needed for test diver
		val props = Properties().also {
			it[StreamsConfig.APPLICATION_ID_CONFIG] = "test"
			it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = "dummy:1234"
			it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = StringSerde::class.java
			it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
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
		processingEventTopic = testDriver.createInputTopic(appConfiguration.kafkaConfig.processingTopic, stringSerde.serializer(), avroProcessingEventSerde.serializer())
	}

	@AfterEach
	fun teardown() {
		testDriver.close()
		MockSchemaRegistry.dropScope(schemaRegistryScope)
	}


	@Test
	fun `Can read empty Event Log`() {
		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was never started`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(key to RECEIVED)
		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(0)
	}

	@Test
	fun `Can read Event Log with Event that was started once`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED
		)
		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(1)
	}

	@Disabled
	@Test
	fun `Can read Event Log with Event that was started once -- input topic first`() {// TODO: Remove test once it works
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(1)
	}

	@Test
	fun `Can read Event Log with Event that was started six times`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to STARTED,
			key to STARTED,
			key to STARTED,
			key to STARTED,
			key to STARTED
		)
		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(6)
	}

	@Test
	fun `Can read Event Log with two Events that were started once`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishProcessingEvents(
			key0 to RECEIVED,
			key0 to STARTED,

			key1 to RECEIVED,
			key1 to STARTED
		)
		publishSoknadsarkivschemas(key0, key1)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0).withCount(1)
		verifyThatScheduler().wasCalled(1).forKey(key1).withCount(1)
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to STARTED,
			key to FINISHED
		)
		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished, but in wrong order`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to FINISHED,
			key to STARTED
		)
		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with one Started and one Finished Event`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishProcessingEvents(
			key0 to RECEIVED,
			key0 to STARTED,

			key1 to RECEIVED,
			key1 to STARTED,
			key1 to FINISHED
		)
		publishSoknadsarkivschemas(key0, key1)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0).withCount(1)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log with mixed order of events`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishProcessingEvents(
			key1 to RECEIVED,
			key0 to RECEIVED,
			key1 to STARTED,
			key0 to STARTED,
			key1 to FINISHED
		)
		publishSoknadsarkivschemas(key0, key1)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0).withCount(1)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log where soknadsarkivschema is missing`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(key to RECEIVED, key to STARTED)

		recreateState()

		verifyThatScheduler().wasNotCalled() // TODO: We'd like an error log when this happens, but there is nothing to consume from the stream.
	}

	@Test
	fun `Can read Event Log where ProcessingEvents are missing`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(0)
	}

	@Test
	fun `Process events, then another event comes in - only the first ones cause scheduling`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED
		)
		publishSoknadsarkivschemas(key)

		recreateState()

		publishProcessingEvents(key to STARTED)

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(1)
	}


	private fun publishSoknadsarkivschemas(vararg keys: String) {
		val keyValues = keys.map { KeyValue(it, soknadarkivschema) }
		inputTopic.pipeKeyValueList(keyValues)
	}

	private fun publishProcessingEvents(vararg keysAndEventTypes: Pair<String, EventTypes>) {
		val keyValues = keysAndEventTypes.map { (key, eventType) -> KeyValue(key, ProcessingEvent(eventType)) }
		processingEventTopic.pipeKeyValueList(keyValues)
	}

	private fun recreateState() {
		StateRecreator(appConfiguration, schedulerService, mock()).recreationStream(StreamsBuilder())
	}

	private fun createRequestData() =
		SoknadarkivschemaBuilder()
			.withBehandlingsid(UUID.randomUUID().toString())
			.withMottatteDokumenter(MottattDokumentBuilder()
				.withMottatteVarianter(MottattVariantBuilder()
					.withUuid(UUID.randomUUID().toString())
					.build())
				.build())
			.build()

	private fun createAppConfiguration() : AppConfiguration {
		val kafkaConfig = AppConfiguration.KafkaConfig(
			inputTopic = "inputTopic",
			processingTopic = "processingTopic",
			schemaRegistryUrl = schemaRegistryUrl,
			servers = "bootstrapServers")
		return AppConfiguration(kafkaConfig)
	}

	private fun verifyThatScheduler() = SchedulerVerifier(schedulerService, soknadarkivschema)
}

private class SchedulerVerifier(private val schedulerService: SchedulerService, private val soknadarkivschema: Soknadarkivschema) {
	private var timesCalled = 0
	private var key: () -> String = { anyString() }
	private var count: () -> Int = { anyInt() }

	fun wasCalled(times: Int): KeyStep {
		timesCalled = times
		return KeyStep(this)
	}

	fun wasNotCalled() {
		verify()
	}

	fun wasNotCalledForKey(key: String) {
		this.key = { eq(key) }
		verify()
	}

	class KeyStep(private val schedulerVerifier: SchedulerVerifier) {
		fun forKey(key: String): CountStep {
			schedulerVerifier.key = { eq(key) }
			return CountStep(schedulerVerifier)
		}
	}

	class CountStep(private val schedulerVerifier: SchedulerVerifier) {
		fun withCount(count: Int) {
			schedulerVerifier.count = { eq(count) }
			schedulerVerifier.verify()
		}
	}

	private fun verify() {
		val value = if (timesCalled > 0) {
			{ eq(soknadarkivschema) }
		} else {
			{ any() }
		}
		verify(schedulerService, times(timesCalled)).schedule(key.invoke(), value.invoke(), count.invoke())
	}
}
