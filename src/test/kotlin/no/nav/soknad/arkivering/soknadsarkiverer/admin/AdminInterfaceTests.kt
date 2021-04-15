package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
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
		loopAndVerify(maxNumberOfAttempts, { getTaskListCount(key) })

		mockFilestorageIsWorking(fileUuid)
		TimeUnit.SECONDS.sleep(2)
		adminInterface.rerun(key)

		loopAndVerify(0, { taskListService.listTasks(key).size })
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
		loopAndVerify(0, { taskListService.listTasks(key).size })
		verifyMockedDeleteRequests(1, appConfiguration.config.filestorageUrl.replace("?", "\\?") + ".*")
		TimeUnit.SECONDS.sleep(2) // Give the system 2 seconds to finish the task after the deletion occurred.

		adminInterface.rerun(key)

		TimeUnit.SECONDS.sleep(1)
		loopAndVerify(0, { taskListService.listTasks(key).size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
	}


	@Test
	fun `No events when requesting allEvents`() {
		val events = adminInterface.allEvents()

		assertTrue(events.isEmpty())
	}

	@Test
	fun `Can request allEvents`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			adminInterface.allEvents()
				.filter { it.innsendingKey == key0 || it.innsendingKey == key1 }
				.count()
		}

		val numberOfInputs = 2
		val numberOfMessages = 1 + maxNumberOfAttempts // 1 "ok" message, a number of mocked exceptions
		val numberOfProcessingEvents = 4 + 3  // 4 for the first event, 3  for the second
		loopAndVerifyAtLeast(numberOfInputs + numberOfMessages + numberOfProcessingEvents, eventsAfter)
	}

	@Test
	fun `Can request unfinishedEvents`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			adminInterface.unfinishedEvents()
				.filter { it.innsendingKey == key0 || it.innsendingKey == key1 }
				.count()
		}

		val numberOfInputs = 2
		val numberOfMessages = maxNumberOfAttempts // mocked exceptions
		val numberOfProcessingEvents = 2 // 1*Started, 1*Failure
		loopAndVerifyAtLeast(numberOfInputs + numberOfMessages + numberOfProcessingEvents, eventsAfter)
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
	fun `Can search for events`() {
		val fileUuid = UUID.randomUUID().toString()
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		val soknadarkivschema0 = createSoknadarkivschema(fileUuid)
		val soknadarkivschema1 = SoknadarkivschemaBuilder()
				.withBehandlingsid("Loussa")
				.withMottatteDokumenter(MottattDokumentBuilder()
					.withMottatteVarianter(listOf(MottattVariantBuilder().withUuid(fileUuid).build()))
				.build())
			.build()
		archiveOneEventSuccessfullyAndFailOne(key0, key1, soknadarkivschema0, soknadarkivschema1)

		val events0 = adminInterface.search("Loussa")
		assertEquals(1, events0.size)
		assertEquals(key1, events0[0].innsendingKey)

		val events1 = adminInterface.search("phrase with no match")
		assertEquals(0, events1.size)

		val events2 = adminInterface.search("Lo.*sa")
		assertEquals(1, events2.size)
		assertEquals(key1, events2[0].innsendingKey)
	}


	@Test
	fun `Querying file that does not exist return message`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnTopic(key, soknadarkivschema)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)


		val nonExistingKey = "non-existing-key"
		val response = adminInterface.filesExists(nonExistingKey)

		assertEquals(1, response.size)
		assertEquals(nonExistingKey, response[0].id)
		assertEquals(FilestorageExistenceStatus.FAILED_TO_FIND_FILE_IDS, response[0].status)
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
		verifyNumberOfStartedEvents(key, 1)


		mockFilestorageIsWorking(listOf(fileUuidInFilestorage to "filecontent", fileUuidNotInFilestorage to null))

		val response = adminInterface.filesExists(key)

		assertEquals(2, response.size)
		assertEquals(fileUuidInFilestorage, response[0].id)
		assertEquals(FilestorageExistenceStatus.EXISTS, response[0].status)
		assertEquals(fileUuidNotInFilestorage, response[1].id)
		assertEquals(FilestorageExistenceStatus.DOES_NOT_EXIST, response[1].status)
	}

	@Test
	fun `Can query if soknadsfillager is healthy`() {
		mockFilestoragePingIsWorking()
		mockJoarkIsWorking()

		val response = adminInterface.pingFilestorage()

		assertEquals(response, "pong")
	}

	private fun archiveOneEventSuccessfullyAndFailOne(key0: String = UUID.randomUUID().toString(),
																										key1: String = UUID.randomUUID().toString()): Pair<Soknadarkivschema, Soknadarkivschema> {
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema0 = createSoknadarkivschema(fileUuid)
		val soknadarkivschema1 = createSoknadarkivschema(fileUuid)

		archiveOneEventSuccessfullyAndFailOne(key0, key1, soknadarkivschema0, soknadarkivschema1)

		return soknadarkivschema0 to soknadarkivschema1
	}

	private fun archiveOneEventSuccessfullyAndFailOne(key0: String, key1: String, soknadarkivschema0: Soknadarkivschema, soknadarkivschema1: Soknadarkivschema) {
		val uuidsAndResponses = listOf(
				soknadarkivschema0.getMottatteDokumenter().flatMap { it.getMottatteVarianter().map { variant -> variant.getUuid() } },
				soknadarkivschema1.getMottatteDokumenter().flatMap { it.getMottatteVarianter().map { variant -> variant.getUuid() } }
			)
			.flatten()
			.distinct()
			.map { fileUuid -> fileUuid to "mocked-response-content-for-$fileUuid" }

		mockFilestorageIsWorking(uuidsAndResponses)
		mockJoarkIsWorking()

		putDataOnTopic(key0, soknadarkivschema0)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)

		mockJoarkIsDown()

		putDataOnTopic(key1, soknadarkivschema1)
		verifyNumberOfStartedEvents(key1, 1)
	}


	private fun verifyNumberOfStartedEvents(key: String, expectedCount: Int) {

		val eventCounter = {
			adminInterface.specificEvent(key)
				.filter { it.type == PayloadType.STARTED }
				.count()
		}
		loopAndVerifyAtLeast(expectedCount, eventCounter)
	}

	private fun getTaskListCount(key: String) = taskListService.listTasks()[key]?.first ?: -1


	private fun putDataOnTopic(key: String, value: Soknadarkivschema, headers: Headers = RecordHeaders()): RecordMetadata {
		keysSentToKafka.add(key)

		val topic = appConfiguration.kafkaConfig.inputTopic

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add(MESSAGE_ID, UUID.randomUUID().toString().toByteArray())
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
