package no.nav.soknad.arkivering.soknadsarkiverer

import com.nhaarman.mockitokotlin2.*
import example.avro.ProcessingEvent
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.config.KafkaProcessingEventProducer
import no.nav.soknad.arkivering.soknadsarkiverer.config.KafkaStreamsConfig
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.Serdes.StringSerde
import org.apache.kafka.streams.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.*
import kotlin.properties.Delegates

@ActiveProfiles("test")
@SpringBootTest
class TopologyTestDriverAvroApplicationTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Autowired
	private lateinit var applicationProperties: ApplicationProperties

	@Autowired
	private lateinit var schedulerService: SchedulerService

	@MockBean
	private lateinit var kafkaProcessingEventProducer: KafkaProcessingEventProducer

	private var maxNumberOfRetries by Delegates.notNull<Int>()

	private val schemaRegistryScope = TopologyTestDriverAvroApplicationTests::class.java.name
	private val mockSchemaRegistryUrl = "mock://$schemaRegistryScope"

	private lateinit var testDriver: TopologyTestDriver
	private lateinit var inputTopic: TestInputTopic<String, Soknadarkivschema>
	private lateinit var inputTopicForBadData: TestInputTopic<String, String>
	private lateinit var processingEventTopic: TestOutputTopic<String, ProcessingEvent>

	private val uuid = UUID.randomUUID().toString()
	private val key = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		setupMockedServices(portToExternalServices!!, applicationProperties.joarkUrl, applicationProperties.filestorageUrl)

		maxNumberOfRetries = applicationProperties.kafkaRetrySleepTime.size

		setupKafkaTopologyTestDriver()
	}

	private fun setupKafkaTopologyTestDriver() {
		val builder = StreamsBuilder()
		KafkaStreamsConfig(applicationProperties, schedulerService).handleStream(builder)
		val topology = builder.build()

		// Dummy properties needed for test diver
		val props = Properties().also {
			it[StreamsConfig.APPLICATION_ID_CONFIG] = "test"
			it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = "dummy:1234"
			it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = StringSerde::class.java
			it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
			it[AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = mockSchemaRegistryUrl
		}

		// Create test driver
		testDriver = TopologyTestDriver(topology, props)
		val schemaRegistry = MockSchemaRegistry.getClientForScope(schemaRegistryScope)

		// Create Serdes used for test record keys and values
		val stringSerde = Serdes.String()
		val avroSoknadarkivschemaSerde = SpecificAvroSerde<Soknadarkivschema>(schemaRegistry)
		val avroProcessingEventSerde = SpecificAvroSerde<ProcessingEvent>(schemaRegistry)

		// Configure Serdes to use the same mock schema registry URL
		val config = hashMapOf(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to mockSchemaRegistryUrl)
		avroSoknadarkivschemaSerde.configure(config, false)
		avroProcessingEventSerde.configure(config, false)

		// Define input and output topics to use in tests
		inputTopic = testDriver.createInputTopic(applicationProperties.inputTopic, stringSerde.serializer(), avroSoknadarkivschemaSerde.serializer())
		inputTopicForBadData = testDriver.createInputTopic(applicationProperties.inputTopic, stringSerde.serializer(), stringSerde.serializer())
		processingEventTopic = testDriver.createOutputTopic(applicationProperties.processingTopic, stringSerde.deserializer(), avroProcessingEventSerde.deserializer())
	}

	@AfterEach
	fun teardown() {
		stopMockedServices()
		testDriver.close()
		MockSchemaRegistry.dropScope(schemaRegistryScope)

		reset(kafkaProcessingEventProducer)
		clearInvocations(kafkaProcessingEventProducer)
	}


	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createRequestData())
		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(2)
		verifyEndProcessingEvents(2)
		verifyMockedPostRequests(2, applicationProperties.joarkUrl)
		verifyDeleteRequestsToFilestorage(2)
	}

	@Test
	@Disabled // TODO: Fix text
	fun `Sending in invalid data will not create Processing Events`() {
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(invalidData)

		// TODO: Delays will always make these pass:
		verifyStartProcessingEvents(0)
		verifyEndProcessingEvents(0)
		// TODO: Verify Message topic?
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsDown()

		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(maxNumberOfRetries + 1)
		verifyEndProcessingEvents(0)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(maxNumberOfRetries + 1)
		verifyEndProcessingEvents(0)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	@Disabled // TODO: Fix text
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this is not deserializable")
		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(1)
		verifyEndProcessingEvents(1)
		verifyMockedPostRequests(1, applicationProperties.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		//TODO: Verify Message topic?
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkRespondsAfterAttempts(1)

		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(2)
		verifyEndProcessingEvents(1)
		verifyMockedPostRequests(2, applicationProperties.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkRespondsAfterAttempts(3)

		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(4)
		verifyEndProcessingEvents(1)
		verifyMockedPostRequests(4, applicationProperties.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
	}

	@Test
	fun `Joark is down -- message ends up on DLQ`() {
		// TODO
	}

	@Test
	fun `Everything works, but Filestorage cannot delete files -- Message is NOT put on retry topic`() {
		mockFilestorageIsWorking(uuid)
		mockFilestorageDeletionIsNotWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(1)
		verifyEndProcessingEvents(1)
		verifyMockedPostRequests(1, applicationProperties.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorkingButGivesInvalidResponse()

		putDataOnKafkaTopic(createRequestData())

		verifyStartProcessingEvents(maxNumberOfRetries + 1)
		verifyEndProcessingEvents(0)
		verifyMockedPostRequests(maxNumberOfRetries + 1, applicationProperties.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Put event on retry topic, then send another event on main topic -- one topic should not lock the other`() {
		// TODO
	}


	private fun verifyStartProcessingEvents(expectedCount: Int) {
		verifyProcessingEvents(expectedCount, "STARTED")
	}

	private fun verifyEndProcessingEvents(expectedCount: Int) {
		verifyProcessingEvents(expectedCount, "ENDED")
	}

	private fun verifyProcessingEvents(expectedCount: Int, value: String) {
		val getCount = {
			mockingDetails(kafkaProcessingEventProducer)
				.invocations.stream()
				.filter { it.arguments[0] == key }
				.filter { it.arguments[1] == value }
				.count()
				.toInt()
		}

		val finalCheck = { verify(kafkaProcessingEventProducer, times(expectedCount)).putDataOnTopic(any(), eq(value), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun putDataOnKafkaTopic(data: Soknadarkivschema) {
		inputTopic.pipeInput(key, data)
	}

	private fun putDataOnKafkaTopic(data: String) {
		inputTopicForBadData.pipeInput(UUID.randomUUID().toString(), data)
	}

	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, applicationProperties.filestorageUrl.replace("?", "\\?") + ".*")
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
