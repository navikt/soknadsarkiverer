package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
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
import org.mockito.ArgumentMatchers.startsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
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
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class ApplicationTests: TopologyTestDriverTests() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

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

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	private var maxNumberOfAttempts by Delegates.notNull<Int>()

	private val fileUuid = UUID.randomUUID().toString()
	private val key = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		maxNumberOfAttempts = appConfiguration.config.retryTime.size

		setupKafkaTopologyTestDriver()
			.withAppConfiguration(appConfiguration)
			.withTaskListService(taskListService)
			.withKafkaPublisher(kafkaPublisherMock)
			.putProcessingEventLogsOnTopic()
			.setup()
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
		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(0, "Exception")
		verifyMetric(1, "get files from filestorage")
		verifyMetric(1, "send files to archive")
		verifyMetric(1, "delete files from filestorage")
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
		verifyMetric(0, "get files from filestorage")
		verifyMetric(0, "send files to archive")
		verifyMetric(0, "delete files from filestorage")
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsDown()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(maxNumberOfAttempts, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyMetric(maxNumberOfAttempts, "get files from filestorage")
		verifyMetric(0, "send files to archive")
		verifyMetric(0, "delete files from filestorage")
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

		verifyProcessingEvents(maxNumberOfAttempts, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyMetric(0, "get files from filestorage")
		verifyMetric(0, "send files to archive")
		verifyMetric(0, "delete files from filestorage")

		assertEquals(getFilestorageErrorsBefore + maxNumberOfAttempts, metrics.getGetFilestorageErrors())
		assertEquals(getFilestorageSuccessesBefore + 0, metrics.getGetFilestorageSuccesses())
		assertEquals(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		assertEquals(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		assertEquals(joarkSuccessesBefore + 0, metrics.getJoarkSuccesses())
		assertEquals(tasksBefore + 1, metrics.getTasks())
		assertEquals(tasksGivenUpOnBefore + 1, metrics.getTasksGivenUpOn())
	}

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
		verifyMetric(1, "get files from filestorage")
		verifyMetric(1, "send files to archive")
		verifyMetric(1, "delete files from filestorage")
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

		verifyProcessingEvents(2, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception")
		verifyMessageStartsWith(1, "ok")
		verifyMetric(2, "get files from filestorage")
		verifyMetric(1, "send files to archive")
		verifyMetric(1, "delete files from filestorage")

		assertEquals(getFilestorageSuccessesBefore + 2, metrics.getGetFilestorageSuccesses())
		assertEquals(delFilestorageSuccessesBefore + 1, metrics.getDelFilestorageSuccesses())
		assertEquals(joarkErrorsBefore + 1, metrics.getJoarkErrors())
		assertEquals(joarkSuccessesBefore + 1, metrics.getJoarkSuccesses())
		assertEquals(tasksBefore + 0, metrics.getTasks(), "Should have created and finished task")
		assertEquals(tasksGivenUpOnBefore + 0, metrics.getTasksGivenUpOn(), "Should not have given up on any task")
	}

	@Test
	fun `Three attempts to Joark fail, the fourth succeeds`() {
		val attemptsToFail = 3
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(attemptsToFail + 1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(attemptsToFail + 1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(attemptsToFail, "Exception")
		verifyMetric(4, "get files from filestorage")
		verifyMetric(1, "send files to archive")
		verifyMetric(1, "delete files from filestorage")
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
		verifyMetric(1, "get files from filestorage")
		verifyMetric(1, "send files to archive")
		verifyMetric(1, "delete files from filestorage") // Metric succeeds even if the operation fails

		assertEquals(getFilestorageSuccessesBefore + 1, metrics.getGetFilestorageSuccesses())
		assertEquals(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		assertEquals(delFilestorageErrorsBefore + 1, metrics.getDelFilestorageErrors())
		assertEquals(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		assertEquals(joarkSuccessesBefore + 1, metrics.getJoarkSuccesses())
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorkingButGivesInvalidResponse()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(maxNumberOfAttempts, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyMockedPostRequests(maxNumberOfAttempts, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyMetric(maxNumberOfAttempts, "get files from filestorage")
		verifyMetric(0, "send files to archive")
		verifyMetric(0, "delete files from filestorage")
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

	private fun verifyMetric(expectedCount: Int, metric: String, key: String = this.key) {
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

		val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putProcessingEventOnTopic(eq(key), eq(type), any()) }
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
			AvsenderMottaker(soknadsarkivschema.getFodselsnummer(), "FNR"),
			Bruker(soknadsarkivschema.getFodselsnummer(), "FNR"),
			DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(soknadsarkivschema.getInnsendtDato()), ZoneOffset.UTC)),
			listOf(
				Dokument(
					soknadsarkivschema.getMottatteDokumenter()[0].getTittel(),
					soknadsarkivschema.getMottatteDokumenter()[0].getSkjemanummer(),
					"SOK",
					listOf(
						DokumentVariant(
							soknadsarkivschema.getMottatteDokumenter()[0].getMottatteVarianter()[0].getFilnavn(),
							"PDFA",
							filestorageContent.toByteArray(),
							soknadsarkivschema.getMottatteDokumenter()[0].getMottatteVarianter()[0].getVariantformat()
						)
					)
				)
			),
			soknadsarkivschema.getBehandlingsid(),
			"INNGAAENDE",
			"NAV_NO",
			soknadsarkivschema.getArkivtema(),
			soknadsarkivschema.getMottatteDokumenter()[0].getTittel()
		)
		assertEquals(expected, requestData)
	}
}
