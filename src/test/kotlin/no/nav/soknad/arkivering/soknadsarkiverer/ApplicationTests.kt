package no.nav.soknad.arkivering.soknadsarkiverer

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaExceptionHandler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaStreamsConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaStreamsConfig.Companion.KAFKA_PUBLISHER
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.Serdes.StringSerde
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TopologyTestDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.startsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

@ActiveProfiles("test")
@SpringBootTest
class ApplicationTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Value("\${application.schema-registry-scope}")
	private val schemaRegistryScope: String = ""

	@Autowired
	private lateinit var appConfiguration: AppConfiguration

	@Autowired
	private lateinit var schedulerService: SchedulerService

	@MockBean
	private lateinit var kafkaPublisherMock: KafkaPublisher

	private var maxNumberOfRetries by Delegates.notNull<Int>()

	private lateinit var testDriver: TopologyTestDriver
	private lateinit var inputTopic: TestInputTopic<String, Soknadarkivschema>
	private lateinit var inputTopicForBadData: TestInputTopic<String, String>

	private val uuid = UUID.randomUUID().toString()
	private val key = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		setupMockedServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		maxNumberOfRetries = appConfiguration.config.retryTime.size

		setupKafkaTopologyTestDriver()
	}

	private fun setupKafkaTopologyTestDriver() {
		val builder = StreamsBuilder()
		KafkaStreamsConfig(appConfiguration, schedulerService, kafkaPublisherMock).handleStream(builder)
		val topology = builder.build()

		// Dummy properties needed for test diver
		val props = Properties().also {
			it[StreamsConfig.APPLICATION_ID_CONFIG] = "test"
			it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = "dummy:1234"
			it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = StringSerde::class.java
			it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
			it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
			it[KAFKA_PUBLISHER] = kafkaPublisherMock
		}

		// Create test driver
		testDriver = TopologyTestDriver(topology, props)
		val schemaRegistry = MockSchemaRegistry.getClientForScope(schemaRegistryScope)

		// Create Serdes used for test record keys and values
		val stringSerde = Serdes.String()
		val avroSoknadarkivschemaSerde = SpecificAvroSerde<Soknadarkivschema>(schemaRegistry)

		// Configure Serdes to use the same mock schema registry URL
		val config = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		avroSoknadarkivschemaSerde.configure(config, false)

		// Define input and output topics to use in tests
		inputTopic = testDriver.createInputTopic(appConfiguration.kafkaConfig.inputTopic, stringSerde.serializer(), avroSoknadarkivschemaSerde.serializer())
		inputTopicForBadData = testDriver.createInputTopic(appConfiguration.kafkaConfig.inputTopic, stringSerde.serializer(), stringSerde.serializer())
	}

	@AfterEach
	fun teardown() {
		stopMockedServices()
		testDriver.close()
		MockSchemaRegistry.dropScope(schemaRegistryScope)

		reset(kafkaPublisherMock)
		clearInvocations(kafkaPublisherMock)
	}


	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createRequestData())
		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(2, STARTED)
		verifyProcessingEvents(2, ARCHIVED)
		verifyProcessingEvents(2, FINISHED)
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(2)
		verifyMessageStartsWith(2, "ok")
		verifyMessageStartsWith(0, "Exception")
	}

	@Test
	fun `Sending in invalid data will not create Processing Events`() {
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(key, invalidData)

		verifyMessageStartsWith(1, "Exception")
		TimeUnit.MILLISECONDS.sleep(500)
		verifyProcessingEvents(0, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyMockedPostRequests(0, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsDown()

		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(maxNumberOfRetries + 1, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfRetries + 1, "Exception")
		verifyMessageStartsWith(0, "ok")
	}

	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(maxNumberOfRetries + 1, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfRetries + 1, "Exception")
		verifyMessageStartsWith(0, "ok")
	}

	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val keyForPoisionPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(keyForPoisionPill, "this is not deserializable")
		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception", keyForPoisionPill)
		verifyMessageStartsWith(1, "ok", key)
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkRespondsAfterAttempts(1)

		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(2, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception")
		verifyMessageStartsWith(1, "ok")
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		val attemptsToFail = 3
		mockFilestorageIsWorking(uuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)

		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(attemptsToFail + 1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(attemptsToFail + 1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(attemptsToFail, "Exception")
	}

	@Test
	fun `Everything works, but Filestorage cannot delete files -- Message is nevertheless marked as finished`() {
		mockFilestorageIsWorking(uuid)
		mockFilestorageDeletionIsNotWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(0, "Exception")
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorkingButGivesInvalidResponse()

		putDataOnKafkaTopic(createRequestData())

		verifyProcessingEvents(maxNumberOfRetries + 1, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyMockedPostRequests(maxNumberOfRetries + 1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfRetries + 1, "Exception")
		verifyMessageStartsWith(0, "ok")
	}


	private fun verifyMessageStartsWith(expectedCount: Int, message: String, key: String = this.key) {
		val getCount = {
			mockingDetails(kafkaPublisherMock)
				.invocations.stream()
				.filter { it.arguments[0] == key }
				.filter { it.arguments[1] is String }
				.filter { (it.arguments[1] as String).startsWith(message) }
				.count()
				.toInt()
		}

		val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putMessageOnTopic(eq(key), startsWith(message), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun verifyProcessingEvents(expectedCount: Int, eventType: EventTypes) {
		val type = ProcessingEvent(eventType)
		val getCount = {
			mockingDetails(kafkaPublisherMock)
				.invocations.stream()
				.filter { it.arguments[0] == key }
				.filter { it.arguments[1] == type }
				.count()
				.toInt()
		}

		val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putProcessingEventOnTopic(eq(key), eq(type), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun putDataOnKafkaTopic(data: Soknadarkivschema) {
		inputTopic.pipeInput(key, data)
	}

	private fun putDataOnKafkaTopic(key: String, data: String) {
		inputTopicForBadData.pipeInput(key, data)
	}

	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, appConfiguration.config.filestorageUrl.replace("?", "\\?") + ".*")
	}

	private fun createRequestData() =
		SoknadarkivschemaBuilder()
			.withBehandlingsid(UUID.randomUUID().toString())
			.withMottatteDokumenter(MottattDokumentBuilder()
				.withMottatteVarianter(MottattVariantBuilder()
					.withUuid(uuid)
					.build())
				.build())
			.build()
}
