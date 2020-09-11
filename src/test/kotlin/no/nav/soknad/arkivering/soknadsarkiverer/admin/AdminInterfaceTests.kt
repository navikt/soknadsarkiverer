package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.STARTED
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit

@Disabled // TODO: Enable when JournalpostClient publishes to Joark
@ActiveProfiles("test")
@SpringBootTest
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(EmbeddedKafkaBrokerConfig::class)
@EmbeddedKafka(topics = ["privat-soknadInnsendt-v1-default", "privat-soknadInnsendt-processingEventLog-v1-default", "privat-soknadInnsendt-messages-v1-default"])
class AdminInterfaceTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Value("\${spring.embedded.kafka.brokers}")
	private val kafkaBrokers: String? = null

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>

	@Autowired
	private lateinit var archiverService: ArchiverService
	@Autowired
	private lateinit var appConfiguration: AppConfiguration
	@Autowired
	private lateinit var taskListService: TaskListService
	@Autowired
	private lateinit var adminInterface: AdminInterface

	private var maxNumberOfAttempts = 0
	private val keysSentToKafka = mutableListOf<String>()

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		assertEquals(kafkaBrokers, appConfiguration.kafkaConfig.servers, "The Kafka bootstrap server property is misconfigured!")

		kafkaProducer = KafkaProducer(kafkaConfigMap())

		maxNumberOfAttempts = appConfiguration.config.retryTime.size
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()

		keysSentToKafka.forEach { taskListService.finishTask(it) }
		keysSentToKafka.clear()
	}


	@Test
	fun `Can call rerun`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnTopic(key, soknadarkivschema)
		verifyProcessingEvents(key, maxNumberOfAttempts, STARTED)
		loopAndVerify(maxNumberOfAttempts + 1, { getTaskListCount(key) })

		mockFilestorageIsWorking(fileUuid)
		adminInterface.rerun(key)

		loopAndVerify(0, { taskListService.listTasks().size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
	}

	@Test
	fun `Rerunning finished task wont cause hanging`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnTopic(key, soknadarkivschema)
		loopAndVerify(0, { taskListService.listTasks().size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)

		adminInterface.rerun(key)

		TimeUnit.SECONDS.sleep(1)
		loopAndVerify(0, { taskListService.listTasks().size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
	}

	@Test
	fun `Rerunning blocked task wont block caller`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking(10_000)

		putDataOnTopic(key, soknadarkivschema)

		val timeBeforeRerun = System.currentTimeMillis()
		adminInterface.rerun(key)

		val timeTaken = System.currentTimeMillis() - timeBeforeRerun
		assertTrue(timeTaken < 100, "This operation should spawn a new thread and should return immediately")
		loopAndVerify(1, { taskListService.listTasks().size })
	}


	@Test
	fun `No events when requesting allEvents`() {
		val events = adminInterface.allEvents()

		assertTrue(events.isEmpty()) // TODO: What if there were events before the test is run?
	}

	@Test
	fun `Can request allEvents`() {
		val eventsBefore = adminInterface.allEvents()

		archiveOneEventSuccessfullyAndFailOne()

		val eventsAfter = adminInterface.allEvents()

		val numberOfInputs = 2
		val numberOfMessages = 1 + maxNumberOfAttempts // 1 "ok" message, a number of mocked exceptions
		val numberOfProcessingEvents = 4 + 1 + 5 // 4 for the first event, 1 received the second, 5 attempts at starting
		assertEquals(eventsBefore.size + numberOfInputs + numberOfMessages + numberOfProcessingEvents, eventsAfter.size)
	}

	@Test
	fun `Can request unfinishedEvents`() {
		val eventsBefore = adminInterface.unfinishedEvents()

		archiveOneEventSuccessfullyAndFailOne()

		val eventsAfter = adminInterface.unfinishedEvents()

		val numberOfInputs = 1
		val numberOfMessages = maxNumberOfAttempts // mocked exceptions
		val numberOfProcessingEvents = 1 + 5 // 4 for the first event, 1 received the second, 5 attempts at starting
		assertEquals(eventsBefore.size + numberOfInputs + numberOfMessages + numberOfProcessingEvents, eventsAfter.size)
	}

	@Test
	fun `Can request specific event`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val events = adminInterface.specificEvent(key0)

		val numberOfInputs = 1
		val numberOfMessages = 1 // "ok" message
		val numberOfProcessingEvents = 4
		assertEquals(numberOfInputs + numberOfMessages + numberOfProcessingEvents, events.size)
	}

	@Test
	fun `Can request eventContent`() {
		archiveOneEventSuccessfullyAndFailOne()

		adminInterface.eventContent(UUID.randomUUID().toString(), LocalDateTime.now())

		//TODO: Create proper test
	}


	@Test
	fun `Querying file that does not exist throws 404`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnTopic(key, soknadarkivschema)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)


		assertThrows<ResponseStatusException> {
			adminInterface.filesExists(UUID.randomUUID().toString())
		}
	}


	@Test
	fun `Can query if file exists`() {
		val key = UUID.randomUUID().toString()
		val fileUuidInFilestorage = UUID.randomUUID().toString()
		val fileUuidNotInFilestorage = UUID.randomUUID().toString()

		val soknadarkivschema = createSoknadarkivschema(listOf(fileUuidInFilestorage, fileUuidNotInFilestorage))
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnTopic(key, soknadarkivschema)
		verifyProcessingEvents(key, maxNumberOfAttempts, STARTED)


		mockFilestorageIsWorking(listOf(fileUuidInFilestorage to "filecontent", fileUuidNotInFilestorage to null))

		val response = adminInterface.filesExists(key)

		assertEquals(2, response.size)
		assertEquals(fileUuidInFilestorage, response[0].id)
		assertEquals("Exists", response[0].status)
		assertEquals(fileUuidNotInFilestorage, response[1].id)
		assertEquals("Does not exist", response[1].status)
	}


	private fun archiveOneEventSuccessfullyAndFailOne(key0: String = UUID.randomUUID().toString(),
																										key1: String = UUID.randomUUID().toString()) {
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema0 = createSoknadarkivschema(listOf(fileUuid))

		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnTopic(key0, soknadarkivschema0)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)

		val soknadarkivschema1 = createSoknadarkivschema(listOf(fileUuid))
		mockJoarkIsDown()

		putDataOnTopic(key1, soknadarkivschema1)
		verifyProcessingEvents(key1, maxNumberOfAttempts, STARTED)
	}


	private fun verifyProcessingEvents(key: String, expectedCount: Int, eventType: EventTypes) {
//		verifyProcessingEvents(kafkaPublisherMock, key, eventType, expectedCount)
		TimeUnit.SECONDS.sleep(10) // TODO
	}

	private fun getTaskListCount(key: String) = getTaskListPair(key).first
	private fun getTaskListPair (key: String) = taskListService.listTasks()[key] ?: error("Expected to find $key in map")



	private fun putDataOnTopic(key: String, value: Soknadarkivschema, headers: Headers = RecordHeaders()): RecordMetadata {
		keysSentToKafka.add(key)

		val topic = appConfiguration.kafkaConfig.inputTopic

		val producerRecord = ProducerRecord(topic, key, value)
		headers.forEach { producerRecord.headers().add(it) }

		return kafkaProducer
			.send(producerRecord)
			.get(1000, TimeUnit.MILLISECONDS) // Blocking call
	}

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "mock://mocked-scope"
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
