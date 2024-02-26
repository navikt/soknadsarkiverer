package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.delay
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilestorageProperties
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationTests : ContainerizedKafka() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties
	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var collectorRegistry: CollectorRegistry
	@Autowired
	private lateinit var filestorageProperties: FilestorageProperties
	@Autowired
	private lateinit var kafkaConfig: KafkaConfig
	@Autowired
	private lateinit var taskListService: TaskListService
	@Autowired
	private lateinit var objectMapper: ObjectMapper
	@Autowired
	private lateinit var metrics: ArchivingMetrics
	@Autowired
	private lateinit var tasklistProperties: TaskListProperties
	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String
	@Value("\${saf.path}")
	private lateinit var safUrl: String

	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>
	private lateinit var kafkaListener: KafkaListener


	private var maxNumberOfAttempts by Delegates.notNull<Int>()

	private val fileUuid = UUID.randomUUID().toString()

	@BeforeAll
	fun setupKafkaProducersAndListeners() {
		kafkaProducer = KafkaProducer(kafkaConfigMap())
		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap()
			.also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })

		kafkaListener = KafkaListener(kafkaConfig)
	}

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(
			portToExternalServices!!,
			journalPostUrl,
			filestorageProperties.files,
			safUrl,
		)

		maxNumberOfAttempts = tasklistProperties.secondsBetweenRetries.size
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
	}

	@AfterAll
	fun stopKafkaConsumers() {
		kafkaListener.close()
	}


	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()
		val soknadsarkivschema = createSoknadarkivschema(key)
		mockSafRequest_notFound(innsendingsId= soknadsarkivschema.behandlingsid)

		putDataOnKafkaTopic(key, soknadsarkivschema)

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "ok" hasCount 1, "Exception" hasCount 0))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 1,
			"send files to archive" hasCount 1,
			"delete files from filestorage" hasCount 1
		))
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Happy case - Putting events on Kafka with duplicate variantFormats for main document will cause filtered rest call to Joark`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString())
		mockFilestorageIsWorking(listOf(fileIds[0] to filestorageContent, fileIds[1] to filestorageContent ))
		mockJoarkIsWorking()
		val soknadsarkivschema = createSoknadarkivschema(fileIds, key)
		mockSafRequest_notFound(innsendingsId= soknadsarkivschema.behandlingsid)

		putDataOnKafkaTopic(key, soknadsarkivschema)

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "ok" hasCount 1, "Exception" hasCount 0))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 1,
			"send files to archive" hasCount 1,
			"delete files from filestorage" hasCount 1
		))
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Sending in invalid data will not create Processing Events`() {
		val key = UUID.randomUUID().toString()
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(key, invalidData)
		mockSafRequest_notFound(innsendingsId= key)

		TimeUnit.MILLISECONDS.sleep(500)
		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 0, STARTED hasCount 0, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 0
		))
		verifyMockedPostRequests(0, journalPostUrl)
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 0,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 0
		))
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsDown()
		mockSafRequest_notFound(innsendingsId= key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount maxNumberOfAttempts, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
		))
		verifyMockedPostRequests(maxNumberOfAttempts, safUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: FAILED" hasCount 1, "ok" hasCount 0, "Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount maxNumberOfAttempts,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 0
		))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Restart task after failing succeeds`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(tasklistProperties.secondsBetweenRetries.size + 1)
		mockSafRequest_notFound(innsendingsId= key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount maxNumberOfAttempts, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
		))
		verifyMockedPostRequests(maxNumberOfAttempts, safUrl)
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })

		val failedKeys = taskListService.getFailedTasks()
		assertTrue(failedKeys.contains(key))

		taskListService.startPaNytt(key)

		verifyProcessingEvents(key, mapOf(FINISHED hasCount 1))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() })
	}


	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val key = UUID.randomUUID().toString()
		val keyForPoisonPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(keyForPoisonPill, "this is not deserializable")
		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("ok" hasCount 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 1,
			"send files to archive" hasCount 1,
			"delete files from filestorage" hasCount 1
		))
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {
		val key = UUID.randomUUID().toString()
		val numberOfFailures = 1
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(numberOfFailures)
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount numberOfFailures+1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMockedPostRequests(numberOfFailures+1, safUrl)
		verifyMockedPostRequests(numberOfFailures+1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("ok" hasCount 1, "Exception" hasCount 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount numberOfFailures+1,
			"send files to archive" hasCount 1,
			"delete files from filestorage" hasCount 1
		))

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 2, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 1, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 1, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 0, { metrics.getTasks() }, "Should have created and finished task")
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() }, "Should not have given up on any task")
	}


	@Test
	fun `First attempt to Joark fails, later found in archive`() {
		val key = UUID.randomUUID().toString()
		val attemptsToFail = 1
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)
		mockSafRequest_foundAfterAttempt(innsendingsId= key, attempts = attemptsToFail)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount attemptsToFail+1, ARCHIVED hasCount 1, FINISHED hasCount 0, FAILURE hasCount 0
		))
		verifyMockedPostRequests(attemptsToFail + 1, safUrl)
		verifyMockedPostRequests(attemptsToFail, journalPostUrl)
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount attemptsToFail,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 1
		))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() }, "Should not have given up on any task")
	}

	@Test
	fun `Everything works, but Filestorage cannot delete files -- Message is nevertheless marked as finished`() {
		val key = UUID.randomUUID().toString()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockFilestorageDeletionIsNotWorking()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "ok" hasCount 1, "Exception" hasCount 0))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 1,
			"send files to archive" hasCount 1,
			"delete files from filestorage" hasCount 1 // Metric succeeds even if the operation fails
		))

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 1, { metrics.getJoarkSuccesses() })
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorkingButGivesInvalidResponse()
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount maxNumberOfAttempts, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
		))
		verifyMockedPostRequests(maxNumberOfAttempts, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("ok" hasCount 0, "Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount maxNumberOfAttempts,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 0
		))
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		Thread.sleep(1000) // Får av og til feil i telling av metrics når alle testene kjøres da metrics endringer i andre tester kan påvirke denne
		val key = UUID.randomUUID().toString()
		val attemptsToFail = 3
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)
		mockSafRequest_notFound(innsendingsId= key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount attemptsToFail+1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMockedPostRequests(attemptsToFail+1, safUrl)
		verifyMockedPostRequests(attemptsToFail + 1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("ok" hasCount 1, "Exception" hasCount attemptsToFail))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount attemptsToFail+1,
			"send files to archive" hasCount 1,
			"delete files from filestorage" hasCount 1
		))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() }, "Should not have given up on any task")
	}


	@Test
	fun `Application already archived will cause finishing archiving`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockAlreadyArchivedResponse(1)
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "ok" hasCount 1, "Exception" hasCount 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 1,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 1
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Application found after calling saf will cause finishing archiving`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockAlreadyArchivedResponse(1)
		mockSafRequest_found(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 0,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 1
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsDown()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount maxNumberOfAttempts, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
		))
		verifyMessageStartsWith(key, mapOf("ok" hasCount 0, "Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 0,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 0
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + maxNumberOfAttempts, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 1, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `All files deleted from Filestorage will cause finishing archiving`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()
		val attemptsToFail = 1

		mockRequestedFileIsGone()
		mockJoarkIsWorking()
		mockSafRequest_foundAfterAttempt_ApplicationTest(innsendingsId= key, attempts = attemptsToFail)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount 2, ARCHIVED hasCount 0, FINISHED hasCount 1, FAILURE hasCount 0
		))
		verifyMessageStartsWith(key, mapOf("ok" hasCount 1, "Exception" hasCount 1))

		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 0,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 1
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Not all files fetched from Filestorage will cause failure`() {
		Thread.sleep(1000) // Får av og til feil i telling av metrics når alle testene kjøres da metrics endringer i andre tester kan påvirke denne
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockRequestedFileIsNotFound()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId= key)

		putDataOnKafkaTopic(key, createSoknadarkivschema(key))

		verifyProcessingEvents(key, mapOf(
			RECEIVED hasCount 1, STARTED hasCount tasksBefore.toInt() + 1, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
		))
		verifyMessageStartsWith(key, mapOf("ok" hasCount 0, "Exception" hasCount 6))

		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" hasCount 0,
			"send files to archive" hasCount 0,
			"delete files from filestorage" hasCount 0
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + 6, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 1, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}


	private fun verifyArchivingMetrics(expected: Double, actual: () -> Double, message: String? = null) {
		loopAndVerify(expected.toInt(), { actual.invoke().toInt() },
			{ assertEquals(expected.toInt(), actual.invoke().toInt(), message) })
	}

	private fun verifyProcessingEvents(key: Key, eventTypeAndCount: Map<EventTypes, Int>) {
		eventTypeAndCount.forEach { (expectedEventType: EventTypes, expectedCount: Int) ->

			val seenEventTypes = {
				kafkaListener.getProcessingEvents()
					.filter { it.key == key }
					.filter { it.value.type == expectedEventType }
					.size
			}

			loopAndVerify(expectedCount, seenEventTypes)
				{ assertEquals(expectedCount, seenEventTypes.invoke(),
					"Expected to see $expectedCount $expectedEventType") }
		}
	}

	private fun verifyMessageStartsWith(key: Key, messageAndCount: Map<String, Int>) {
		messageAndCount.forEach { (expectedMessage: String, expectedCount: Int) ->

			val seenMessages = {
				kafkaListener.getMessages()
					.filter { it.key == key }
					.filter { it.value.startsWith(expectedMessage) }
					.size
			}

			loopAndVerify(expectedCount, seenMessages)
				{ assertEquals(expectedCount, seenMessages.invoke(),
					"Expected to see $expectedCount messages starting with '$expectedMessage'") }
		}
	}

	private fun verifyKafkaMetric(key: Key, metricAndCount: Map<String, Int>) {
		metricAndCount.forEach { (expectedMetric: String, expectedCount: Int) ->

			val seenMetrics = {
				kafkaListener.getMetrics()
					.filter { it.key == key }
					.filter { it.value.action == expectedMetric }
					.size
			}

			loopAndVerify(expectedCount, seenMetrics)
				{ assertEquals(expectedCount, seenMetrics.invoke(), "Expected to see $expectedCount '$expectedMetric'") }
		}
	}


	private fun createSoknadarkivschema(behandlingsId: String) = createSoknadarkivschema(fileUuid, behandlingsId)


	private fun verifyRequestDataToJoark(soknadsarkivschema: Soknadarkivschema, requestData: OpprettJournalpostRequest) {
		val expected = OpprettJournalpostRequest(
			AvsenderMottaker(soknadsarkivschema.fodselsnummer, "FNR"),
			Bruker(soknadsarkivschema.fodselsnummer, "FNR"),
			DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond(soknadsarkivschema.innsendtDato), ZoneId.of("Europe/Oslo"))),
			listOf(
				Dokument(
					soknadsarkivschema.mottatteDokumenter[0].tittel,
					soknadsarkivschema.mottatteDokumenter[0].skjemanummer,
					"SOK",
					listOf(
						DokumentVariant(
							soknadsarkivschema.mottatteDokumenter[0].mottatteVarianter[0].filnavn,
							"PDFA",
							filestorageContent.toByteArray(),
							soknadsarkivschema.mottatteDokumenter[0].mottatteVarianter[0].variantformat
						)
					)
				)
			),
			soknadsarkivschema.behandlingsid,
			"INNGAAENDE",
			"NAV_NO",
			soknadsarkivschema.arkivtema,
			soknadsarkivschema.mottatteDokumenter[0].tittel
		)
		assertEquals(expected, requestData)
	}


	private fun putDataOnKafkaTopic(key: Key, soknadarkivschema: Soknadarkivschema, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.mainTopic
		putDataOnTopic(key, soknadarkivschema, headers, topic, kafkaProducer)
	}

	private fun putDataOnKafkaTopic(key: Key, badData: String, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.mainTopic
		putDataOnTopic(key, badData, headers, topic, kafkaProducerForBadData)
	}

	private fun <T> putDataOnTopic(key: Key, value: T, headers: Headers, topic: String,
																 kafkaProducer: KafkaProducer<String, T>): RecordMetadata {

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

	private infix fun <A> A.hasCount(count: Int) = this to count
}
