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
import org.mockito.Mockito
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
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(STARTED)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(STARTED))}
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(ARCHIVED)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(ARCHIVED))}
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(FINISHED)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(FINISHED))}
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(FAILURE)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(FAILURE))}


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
		TimeUnit.SECONDS.sleep(9)

		verifyProcessingEvents(1, RECEIVED)
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
		TimeUnit.SECONDS.sleep(9)

		verifyProcessingEvents(1, FAILURE)
		verifyProcessingEvents(1, STARTED)
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
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val keyForPoisionPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(keyForPoisionPill, "this is not deserializable")
		putDataOnKafkaTopic(createSoknadarkivschema())
		TimeUnit.SECONDS.sleep(2)

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

	private fun verifyMessageStartsWith(expectedCount: Int, message: String, key: String = this.key) {
		verifyMessageStartsWithUtils(kafkaPublisherMock, expectedCount, message, key)
	}

	private fun verifyMetric(expectedCount: Int, metric: String, key: String = this.key) {
		verifyMetricUtils(kafkaPublisherMock, expectedCount, metric, key)
	}

	private fun verifyProcessingEvents(expectedCount: Int, eventType: EventTypes) {
		verifyProcessingEventsUtils(kafkaPublisherMock, expectedCount, eventType, key)
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
