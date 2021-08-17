package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class ApplicationTests: TopologyTestDriverTests() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockBean
	private lateinit var collectorRegistry: CollectorRegistry

	@Autowired
	private lateinit var appConfiguration: AppConfiguration

	@Autowired
	private lateinit var taskListService: TaskListService

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@Autowired
	private lateinit var metrics: ArchivingMetrics

	@MockBean
	private lateinit var kafkaPublisherMock: KafkaPublisher

	private var maxNumberOfAttempts by Delegates.notNull<Int>()

	private val fileUuid = UUID.randomUUID().toString()
	private val key = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		maxNumberOfAttempts = appConfiguration.config.retryTime.size

		fun mockPuttingProcessingEventOnTopic(eventType: EventTypes) {
			whenever(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(eventType)), any()))
				.doAnswer { putDataOnProcessingTopic(key, ProcessingEvent(eventType)) }
		}
		EventTypes.values().forEach { mockPuttingProcessingEventOnTopic(it) }


		setupKafkaTopologyTestDriver()
			.withAppConfiguration(appConfiguration)
			.withTaskListService(taskListService)
			.withKafkaPublisher(kafkaPublisherMock)
			.putProcessingEventLogsOnTopic()
			.setup(metrics)
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
		closeTestDriver()
		MockSchemaRegistry.dropScope(schemaRegistryScope)

		reset(kafkaPublisherMock)
		clearInvocations(kafkaPublisherMock)
	}


	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()
		val soknadsarkivschema = createSoknadarkivschema()

		putDataOnKafkaTopic(soknadsarkivschema)

		verifyProcessingEvents(1, RECEIVED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(0, "Exception")
		verifyKafkaMetric(1, "get files from filestorage")
		verifyKafkaMetric(1, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage")
		val requests = verifyPostRequest(appConfiguration.config.joarkUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
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
		verifyKafkaMetric(0, "get files from filestorage")
		verifyKafkaMetric(0, "send files to archive")
		verifyKafkaMetric(0, "delete files from filestorage")
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsDown()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, FAILURE)
		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyKafkaMetric(maxNumberOfAttempts, "get files from filestorage")
		verifyKafkaMetric(0, "send files to archive")
		verifyKafkaMetric(0, "delete files from filestorage")
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1,	metrics.getTasksGivenUpOn()) }

	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val keyForPoisionPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(keyForPoisionPill, "this is not deserializable")
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception", keyForPoisionPill)
		verifyMessageStartsWith(1, "ok", key)
		verifyKafkaMetric(1, "get files from filestorage")
		verifyKafkaMetric(1, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage")
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(1)

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception")
		verifyMessageStartsWith(1, "ok")
		verifyKafkaMetric(2, "get files from filestorage")
		verifyKafkaMetric(1, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage")

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 2, metrics.getGetFilestorageSuccesses())
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 1, metrics.getDelFilestorageSuccesses())
		verifyArchivingMetrics(joarkErrorsBefore + 1, metrics.getJoarkErrors())
		verifyArchivingMetrics(joarkSuccessesBefore + 1, metrics.getJoarkSuccesses())
		verifyArchivingMetrics(tasksBefore + 0, metrics.getTasks(), "Should have created and finished task")
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, metrics.getTasksGivenUpOn(), "Should not have given up on any task")
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		val attemptsToFail = 3
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(attemptsToFail + 1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(attemptsToFail, "Exception")
		verifyKafkaMetric(4, "get files from filestorage")
		verifyKafkaMetric(1, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage")
	}

	@Test
	fun `Everything works, but Filestorage cannot delete files -- Message is nevertheless marked as finished`() {
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val delFilestorageErrorsBefore = metrics.getDelFilestorageErrors()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockFilestorageDeletionIsNotWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(0, "Exception")
		verifyKafkaMetric(1, "get files from filestorage")
		verifyKafkaMetric(1, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage") // Metric succeeds even if the operation fails

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, metrics.getGetFilestorageSuccesses())
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		verifyArchivingMetrics(delFilestorageErrorsBefore + 1, metrics.getDelFilestorageErrors())
		verifyArchivingMetrics(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		verifyArchivingMetrics(joarkSuccessesBefore + 1, metrics.getJoarkSuccesses())
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorkingButGivesInvalidResponse()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, RECEIVED)
		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyProcessingEvents(1, FAILURE)
		verifyMockedPostRequests(maxNumberOfAttempts, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyKafkaMetric(maxNumberOfAttempts, "get files from filestorage")
		verifyKafkaMetric(0, "send files to archive")
		verifyKafkaMetric(0, "delete files from filestorage")
	}



	@Test
	fun `Application already archived will cause finishing archiving`() {
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockAlreadyArchivedResponse(1)

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyProcessingEvents(0, FAILURE)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyKafkaMetric(1, "get files from filestorage")
		verifyKafkaMetric(0, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage")

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, metrics.getGetFilestorageErrors())
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, metrics.getGetFilestorageSuccesses())
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 1, metrics.getDelFilestorageSuccesses())
		verifyArchivingMetrics(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		verifyArchivingMetrics(joarkSuccessesBefore + 0, metrics.getJoarkSuccesses())
		verifyArchivingMetrics(tasksBefore, metrics.getTasks())
		verifyArchivingMetrics(tasksGivenUpOnBefore, metrics.getTasksGivenUpOn())
	}

	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyProcessingEvents(1, FAILURE)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyKafkaMetric(0, "get files from filestorage")
		verifyKafkaMetric(0, "send files to archive")
		verifyKafkaMetric(0, "delete files from filestorage")

		verifyArchivingMetrics(getFilestorageErrorsBefore + maxNumberOfAttempts, metrics.getGetFilestorageErrors())
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, metrics.getGetFilestorageSuccesses())
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		verifyArchivingMetrics(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		verifyArchivingMetrics(joarkSuccessesBefore + 0, metrics.getJoarkSuccesses())
		verifyArchivingMetrics(tasksBefore + 1, metrics.getTasks())
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, metrics.getTasksGivenUpOn())
	}

	@Test
	fun `Files deleted from Filestorage will cause finishing archiving`() {
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockRequestedFileIsGone()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyProcessingEvents(0, FAILURE)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyKafkaMetric(0, "get files from filestorage")
		verifyKafkaMetric(0, "send files to archive")
		verifyKafkaMetric(1, "delete files from filestorage")

		verifyArchivingMetrics(getFilestorageErrorsBefore + 1, metrics.getGetFilestorageErrors())
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, metrics.getGetFilestorageSuccesses())
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		verifyArchivingMetrics(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		verifyArchivingMetrics(joarkSuccessesBefore + 0, metrics.getJoarkSuccesses())
		verifyArchivingMetrics(tasksBefore, metrics.getTasks())
		verifyArchivingMetrics(tasksGivenUpOnBefore, metrics.getTasksGivenUpOn())
	}


	private fun verifyArchivingMetrics(expected: Double, actual: Double, message: String? = null) {
		loopAndVerify(expected.toInt(), { actual.toInt() }, { assertEquals(expected, actual, message) })
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

		val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putMessageOnTopic(eq(key),
			ArgumentMatchers.startsWith(message), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun verifyKafkaMetric(expectedCount: Int, metric: String, key: String = this.key) {
		val getCount = {
			mockingDetails(kafkaPublisherMock)
				.invocations.stream()
				.filter { it.arguments[0] == key }
				.filter { it.arguments[1] is InnsendingMetrics }
				.filter { (it.arguments[1] as InnsendingMetrics).toString().contains(metric) }
				.count()
				.toInt()
		}

		loopAndVerify(expectedCount, getCount)
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

		val finalCheck = { verify(kafkaPublisherMock, atLeast(expectedCount)).putProcessingEventOnTopic(eq(key), eq(type), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun putDataOnKafkaTopic(data: Soknadarkivschema) {
		putDataOnInputTopic(key, data)
	}

	private fun putDataOnKafkaTopic(key: String, data: String) {
		putBadDataOnInputTopic(key, data)
	}

	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, appConfiguration.config.filestorageUrl.replace("?", "\\?") + ".*")
	}

	private fun createSoknadarkivschema() = createSoknadarkivschema(fileUuid)


	private fun verifyRequestDataToJoark(soknadsarkivschema: Soknadarkivschema, requestData: OpprettJournalpostRequest) {
		val expected = OpprettJournalpostRequest(
			AvsenderMottaker(soknadsarkivschema.fodselsnummer, "FNR"),
			Bruker(soknadsarkivschema.fodselsnummer, "FNR"),
			DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(soknadsarkivschema.innsendtDato), ZoneOffset.UTC)),
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
}
