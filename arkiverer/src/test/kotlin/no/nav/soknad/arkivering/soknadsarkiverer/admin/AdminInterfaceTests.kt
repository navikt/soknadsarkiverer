package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListProperties
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminInterfaceTests : ContainerizedKafka() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockBean
	private lateinit var collectorRegistry: CollectorRegistry


	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>

	@Autowired
	private lateinit var appConfiguration: AppConfiguration
	@Autowired
	private lateinit var kafkaConfig: KafkaConfig
	@Autowired
	private lateinit var taskListService: TaskListService
	@Autowired
	private lateinit var adminInterface: ApplicationAdminInterface
	@Autowired
	private lateinit var metricsInterface: MetricsInterface
	@Autowired
	private lateinit var taskListProperties: TaskListProperties
	@Value("\${joark.journal-post}")
	private lateinit var joarnalPostUrl: String
	private var maxNumberOfAttempts = 0
	private val keysSentToKafka = mutableListOf<String>()

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, joarnalPostUrl, appConfiguration.config.filestorageUrl)

		kafkaProducer = KafkaProducer(kafkaConfigMap())

		maxNumberOfAttempts = taskListProperties.secondsBetweenRetries.size
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
		verifyMockedPostRequests(1, joarnalPostUrl)
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
		verifyMockedDeleteRequests(1, appConfiguration.config.filestorageUrl + ".*")
		TimeUnit.SECONDS.sleep(2) // Give the system 2 seconds to finish the task after the deletion occurred.

		adminInterface.rerun(key)

		TimeUnit.SECONDS.sleep(1)
		loopAndVerify(0, { taskListService.listTasks(key).size })
		verifyMockedPostRequests(1, joarnalPostUrl)
	}


	@Test
	fun `Can request allEvents`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			adminInterface.allEvents().count { it.innsendingKey == key0 || it.innsendingKey == key1 }
		}

		val numberOfMainRecords = 2
		val numberOfMessages = 1 + maxNumberOfAttempts // 1 "ok" message, a number of mocked exceptions
		val numberOfProcessingEvents = 4 + 3  // 4 for the first event, 3 for the second
		val numberOfMetricEvents = 3 + maxNumberOfAttempts // 3 for the successful event, maxNumberOfAttempts getFiles-events for the failing
		loopAndVerifyAtLeast(numberOfMainRecords + numberOfMessages + numberOfProcessingEvents + numberOfMetricEvents, eventsAfter)
	}

	@Test
	fun `Can request unfinishedEvents`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			adminInterface.unfinishedEvents().count { it.innsendingKey == key0 || it.innsendingKey == key1 }
		}

		val numberOfMainRecords = 2
		val numberOfMessages = maxNumberOfAttempts // mocked exceptions
		val numberOfProcessingEvents = 2 // 1*Started, 1*Failure
		val numberOfMetricEvents = maxNumberOfAttempts // maxNumberOfAttempts getFiles-events for the failing
		loopAndVerifyAtLeast(numberOfMainRecords + numberOfMessages + numberOfMetricEvents + numberOfProcessingEvents, eventsAfter)
	}

	@Test
	fun `Can request failedEvents`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			adminInterface.failedEvents().count { it.innsendingKey == key1 }
		}

		loopAndVerify(1, eventsAfter)
	}

	@Test
	fun `Can request specific event`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val events = adminInterface.specificEvent(key0)

		val numberOfMainRecords = 1
		val numberOfMessages = 1 // "ok" message
		val numberOfProcessingEvents = 4
		val numberOfMetricEvents = 3 // 3 for the successful event
		assertEquals(numberOfMainRecords + numberOfMessages + numberOfMetricEvents + numberOfProcessingEvents, events.size)
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
		verifyMockedPostRequests(1, joarnalPostUrl)


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


	@Test
	fun `Can request metrics`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			metricsInterface.metrics()
				.filter { it.key == key0 || it.key == key1 }
				.flatMap { it.datapoints }
				.count()
		}

		val numberOfMetricEvents = 3 + maxNumberOfAttempts // 3 for the successful event, maxNumberOfAttempts getFiles-events for the failing
		val numberOfProcessingEvents = 4 + (1 + maxNumberOfAttempts + 1) // 4 for the successful event + (1 RECEIVED + maxNumberOfAttempts STARTED + 1 FAILED)
		loopAndVerifyAtLeast(numberOfProcessingEvents + numberOfMetricEvents, eventsAfter)
	}

	@Test
	fun `Can request specific metrics`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		archiveOneEventSuccessfullyAndFailOne(key0, key1)

		val eventsAfter = {
			metricsInterface.specificMetrics(key0)
				.flatMap { it.datapoints }
				.count()
		}

		val numberOfMetricEvents = 3 // 3 for the successful event
		val numberOfProcessingEvents = 4 // 4 for the successful event
		loopAndVerifyAtLeast(numberOfProcessingEvents + numberOfMetricEvents, eventsAfter)
	}


	private fun archiveOneEventSuccessfullyAndFailOne(
		key0: String = UUID.randomUUID().toString(),
		key1: String = UUID.randomUUID().toString()
	): Pair<Soknadarkivschema, Soknadarkivschema> {

		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema0 = createSoknadarkivschema(fileUuid)
		val soknadarkivschema1 = createSoknadarkivschema(fileUuid)

		archiveOneEventSuccessfullyAndFailOne(key0, key1, soknadarkivschema0, soknadarkivschema1)

		return soknadarkivschema0 to soknadarkivschema1
	}

	private fun archiveOneEventSuccessfullyAndFailOne(
		key0: String,
		key1: String,
		soknadarkivschema0: Soknadarkivschema,
		soknadarkivschema1: Soknadarkivschema
	) {

		val uuidsAndResponses = listOf(
				soknadarkivschema0.mottatteDokumenter.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } },
				soknadarkivschema1.mottatteDokumenter.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
			)
			.flatten()
			.distinct()
			.map { fileUuid -> fileUuid to "mocked-response-content-for-$fileUuid" }

		mockFilestorageIsWorking(uuidsAndResponses)
		mockJoarkIsWorking()

		putDataOnTopic(key0, soknadarkivschema0)
		verifyMockedPostRequests(1, joarnalPostUrl)

		mockJoarkIsDown()

		putDataOnTopic(key1, soknadarkivschema1)
		verifyNumberOfStartedEvents(key1, 1)
	}


	private fun verifyNumberOfStartedEvents(key: String, @Suppress("SameParameterValue") expectedCount: Int) {

		val eventCounter = {
			adminInterface.specificEvent(key).count { it.type == PayloadType.STARTED }
		}
		loopAndVerifyAtLeast(expectedCount, eventCounter)
	}

	private fun getTaskListCount(key: String) = taskListService.listTasks()[key]?.first ?: -1


	private fun putDataOnTopic(key: String, value: Soknadarkivschema, headers: Headers = RecordHeaders()): RecordMetadata {
		keysSentToKafka.add(key)

		val topic = kafkaConfig.topics.mainTopic

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
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
