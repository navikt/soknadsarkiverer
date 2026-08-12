package no.nav.soknad.arkivering.soknadsarkiverer

import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.fasterxml.jackson.module.kotlin.readValue
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.eq
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.lang.Thread.sleep
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates


@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationTests : ContainerizedKafka() {

	@MockitoBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null // 2private lateinit var ... 908

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

	@MockitoSpyBean
	private lateinit var kafkaPublisher: KafkaPublisher

	@MockitoSpyBean
	private lateinit var archiverService: ArchiverService

	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String

	@Value("\${saf.path}")
	private lateinit var safUrl: String

	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>
	private lateinit var kafkaListener: KafkaListener
	private lateinit var kafkaNologinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaloggedinTopicProducer: KafkaProducer<String, String>


	private var maxNumberOfAttempts by Delegates.notNull<Int>()

	private val fileUuid = UUID.randomUUID().toString()

	companion object {

		@JvmField
		@RegisterExtension
		val wireMock: WireMockExtension = WireMockExtension.newInstance()
			.configureStaticDsl(true)
			.options(
				wireMockConfig()
					.port(2908)
					.notifier(ConsoleNotifier(true))
					.withRootDirectory("src/test/resources")
					.asynchronousResponseEnabled(false)
			)
			.build()

		@JvmStatic
		@DynamicPropertySource
		fun properties(reg: DynamicPropertyRegistry) {
			//val base = "http://localhost:${wireMock.port}"
			reg.add("innsendingsapi.path") { "/innsendte/v1/files/[0-9a-fA-F-]{36}" }
			reg.add("joark.journal-post") { "/rest/journalpostapi/v1/journalpost" }
			reg.add("saf.path") { "/graphql" }
		}
	}

	@BeforeAll
	fun setupKafkaProducersAndListeners() {
		kafkaListener = KafkaListener(kafkaConfig)
	}


	@AfterAll
	fun teardownKafka() {
		kafkaListener.close()
	}

	@BeforeEach
	fun setup() {
		wireMock.resetAll()
		setupMockedNetworkServices(
			wireMock,
			portToExternalServices!!,
			journalPostUrl,
			"/innsendte/v1/files",
			safUrl,
		)
		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap()
			.also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })
		kafkaNologinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})
		kafkaloggedinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})

		maxNumberOfAttempts = tasklistProperties.secondsBetweenRetries.size
	}

	@AfterEach
	fun teardown() {
		//stopMockedNetworkServices()
		//kafkaListener.clear(MockkClear())
		wireMock.resetAll()

		kafkaProducerForBadData.close()
		kafkaNologinTopicProducer.close()
		kafkaloggedinTopicProducer.close()

		metrics.unregister()
		taskListService.clearLoggedTaskStates()
	}

	@Test
	fun `Happy case - Putting events on Kafka main topic will cause rest calls to Joark`() {
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val mainDocumentTitle = "Test dokument"
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, fileIds))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileIds.map { it to filestorageContent })
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "Exception" hasCount 0))
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Happy case - Received message archieved, but feedback to innsender fails state set to finished`() {
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val mainDocumentTitle = "Test dokument"
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, fileIds))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileIds.map { it to filestorageContent })
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		doThrow(RuntimeException("Failed to send arkiveringstilbakemelding")).whenever(kafkaPublisher)
			.putArkiveringstilbakemeldingOnTopic(any(), any(), any())
		doNothing().whenever(kafkaPublisher)
			.putMessageOnTopic(eq(key), eq("**Archiving: OK."), any())

		putDataOnKafkaTopic(soknadsarkivschema)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount maxNumberOfAttempts, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Happy case - File missing, new event event on Kafka for key will cause rest calls to Joark`() {
		val fileId = UUID.randomUUID().toString()
		val mainDocumentTitle = "Test dokument"
		mockFilestorageIsWorking(fileId)

		mockJoarkIsWorking()
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, listOf("non-existing-file-id")))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount maxNumberOfAttempts, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 1
			)
		)

		sleep(1000)

		val updatedSchema = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel(mainDocumentTitle)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, mainDocumentTitle, listOf(fileId)))
			)
			.build()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		putDataOnKafkaTopic(updatedSchema)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 2, STARTED hasCount maxNumberOfAttempts+1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 1
			)
		)

		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(updatedSchema, request)
	}


	@Test
	fun `Reject updated application on kafka when already archived`() {
		val fileId = UUID.randomUUID().toString()
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel("Test dokument")
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, "Test dokument", listOf(fileId)))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)

		sleep(1000)

		val updatedFileId = UUID.randomUUID().toString()
		val dokument = soknadsarkivschema.dokumenter.first()
		val variant = dokument.varianter.first().copy(uuid=updatedFileId)
		val updatedDokument = dokument.copy(varianter = listOf(variant))
		val updatedSchema = soknadsarkivschema.copy(dokumenter = listOf(updatedDokument))

		mockFilestorageIsWorking(updatedFileId)
		mockJoarkIsWorking()
		mockSafRequest_found(innsendingsId = key)

		putDataOnKafkaTopic(updatedSchema)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 2, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)

	}

	@Test
	fun `Happy case - File missing, new event event on Kafka topic for key will cause rest call to Joark`() {
		val index = 0
		val processingStates = mapOf(RECEIVED to index, STARTED to index, ARCHIVED to index, FINISHED to index, FAILURE to index)
		wrongfileListInMessageThenNewInnsendingWithCorrectionAndArchiving(processingStates)
	}

	fun wrongfileListInMessageThenNewInnsendingWithCorrectionAndArchiving(processingStates: Map<EventTypes, Int>) {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val soknadarkivschema = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
				TestDokument("W1", false, tittel = "W2 vedlegg", listOf(UUID.randomUUID().toString())) // extra document
			))
			.build()

		mockJoarkIsWorking()
		// FileStorage is missing extra document
		mockFilestorageIsWorking(fileIds.map { it to filestorageContent })
		mockSafRequest_notFound(innsendingsId = soknadarkivschema.innsendingsId)

		putDataOnKafkaTopic(soknadarkivschema)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount processingStates.get(RECEIVED)!!+1, STARTED hasCount processingStates.get(STARTED)!!+maxNumberOfAttempts,
				ARCHIVED hasCount processingStates.get(ARCHIVED)!!, FINISHED hasCount processingStates.get(FINISHED)!!, FAILURE hasCount processingStates.get(FAILURE)!! + 1
			)
		)

		sleep(1000)

		// Given updated soknad without extra document
		val updatedSoknadarkivschema = InnsendingTopicMsgBuilder()
			.withInnsendingsId(soknadarkivschema.innsendingsId)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		putDataOnKafkaTopic(updatedSoknadarkivschema)

		// Expect updated soknadarkivschema to be archived and rest call to joark to be made with updated soknadarkivschema
		verifyProcessingEvents(
				key, mapOf(
				RECEIVED hasCount processingStates.get(RECEIVED)!!+2, STARTED hasCount processingStates.get(STARTED)!!+maxNumberOfAttempts+1,
				ARCHIVED hasCount processingStates.get(ARCHIVED)!!+1, FINISHED hasCount processingStates.get(FINISHED)!!+1, FAILURE hasCount processingStates.get(FAILURE)!!+1
			)
		)

		val requests = verifyPostRequest(journalPostUrl)
		assertTrue(requests.isNotEmpty())
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests.last().body)
		verifyRequestDataToJoark(updatedSoknadarkivschema, request)
	}

	@Test
	fun `Repeat - File missing, new event event on Kafka main topic for for key will cause rest calls to Joark`() {
		repeat(10) {
			try {
				val loop = 0
				val processingStates = mapOf(RECEIVED to loop, STARTED to loop, ARCHIVED to loop, FINISHED to loop, FAILURE to loop)

				wrongfileListInMessageThenNewInnsendingWithCorrectionAndArchiving(processingStates)
			} catch (e: Exception) {
				throw e
			}
		}
	}

	@Test
	fun rejectUpdatedApplicationOnKafkaWhenAlreadyArchived() {

		val fileId = UUID.randomUUID().toString()
		val soknadsarkivschema = InnsendingTopicMsgBuilder()
			.withTittel("Test dokument")
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(
				mutableListOf(TestDokument("NAV 11-12.10", true, "Test dokument", listOf(fileId)))
			)
			.build()
		val key = soknadsarkivschema.innsendingsId

		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)

		sleep(1000)

		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()
		mockSafRequest_found(innsendingsId = key)

		putDataOnKafkaTopic(soknadsarkivschema)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 2, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)

	}

	@Test
	fun `Happy case - Several loggedIn and noLogin Events on Kafka will cause rest calls to Joark`() {
		mockJoarkIsWorking()
		val noOfRepeates = 333
		repeat(noOfRepeates) { index ->

			val key = UUID.randomUUID().toString()
			// Given loggedInMsg, noLoggInMsg or soknadsarkivschema
			val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())

			mockFilestorageIsWorking((listOf(fileUuid)+fileIds).map{ it to filestorageContent })
			mockSafRequest_notFound(innsendingsId = key)
			if (index % 2 == 0) {
				val noLoggInMsg = InnsendingTopicMsgBuilder()
					.withInnsendingsId(key)
					.withTittel("Test dokument")
					.withKanal("NAV_NO_UINNLOGGET")
					.withTestDokumenter(mutableListOf(
						TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
					))
					.build()
				putDataOnKafkaTopic(noLoggInMsg)
			} else {
				val loggedInMsg = InnsendingTopicMsgBuilder()
					.withInnsendingsId(key)
					.withTittel("Test dokument")
					.withKanal("NAV_NO")
					.withTestDokumenter(mutableListOf(
						TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
					))
					.build()
				putDataOnKafkaTopic(loggedInMsg)
			}
		}
		verifyMockedPostRequests(noOfRepeates, safUrl)
		verifyMockedPostRequests(noOfRepeates, journalPostUrl)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(noOfRepeates, requests.size)

	}


	@Test
	fun `Happy case - Putting events on Kafka with duplicate variantFormats for main document will cause filtered rest call to Joark`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = loggedInMsg.innsendingsId)

		putDataOnKafkaTopic(loggedInMsg)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "Exception" hasCount 0))
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
		val requests = verifyPostRequest(journalPostUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		assertEquals(loggedInMsg.dokumenter.first { it.erHovedskjema }.varianter.size-1, request.dokumenter.first().dokumentvarianter.size)
	}

	@Test
	fun `Sending in invalid data will not create Processing Events`() {
		val key = UUID.randomUUID().toString()
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(key, invalidData, RecordHeaders())
		mockSafRequest_notFound(innsendingsId = key)

		TimeUnit.MILLISECONDS.sleep(500)
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 0, STARTED hasCount 0, ARCHIVED hasCount 0, FINISHED hasCount 0, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(0, journalPostUrl)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsDown()
		mockSafRequest_notFound(innsendingsId = key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMockedPostRequests(maxNumberOfAttempts, safUrl)
		verifyMessageStartsWith(
			key,
			mapOf("**Archiving: FAILED" hasCount 1, "ok" hasCount 0, "Exception" hasCount maxNumberOfAttempts)
		)
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: FAILED" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount maxNumberOfAttempts,
				"send files to archive" hasCount 0,
			)
		)
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Restart task after failing succeeds`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsDown()
		mockSafRequest_notFound(innsendingsId = key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMockedPostRequests(maxNumberOfAttempts, safUrl)
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })

		val failedKeys = taskListService.getFailedTasks()
		assertTrue(failedKeys.contains(key))

		// When
		mockJoarkIsWorking()
		taskListService.startPaNytt(key)

		// Expect
		verifyProcessingEvents(key, mapOf(FINISHED hasCount 1))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() })
	}


	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val keyForPoisonPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		putDataOnTopic(keyForPoisonPill, "this is not deserializable", RecordHeaders(), topic= kafkaConfig.topics.loggedinSubmissionTopic,
			kafkaProducer = kafkaloggedinTopicProducer)
		putDataOnKafkaTopic(loggedInMsg)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(1, safUrl)
		verifyMockedPostRequests(1, journalPostUrl)
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 1,
			)
		)
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {

		// Given
		val numberOfFailures = 1
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(numberOfFailures)
		mockSafRequest_notFound(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount numberOfFailures + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(numberOfFailures + 1, safUrl)
		verifyMockedPostRequests(numberOfFailures + 1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount numberOfFailures + 1,
				"send files to archive" hasCount 1,
			)
		)

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 2, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 1, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 1, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 0, { metrics.getTasks() }, "Should have created and finished task")
		verifyArchivingMetrics(
			tasksGivenUpOnBefore + 0,
			{ metrics.getTasksGivenUpOn() },
			"Should not have given up on any task"
		)
	}


	@Test
	fun `First attempt to Joark fails, later found in archive`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val attemptsToFail = 1
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(attemptsToFail)
		mockSafRequest_foundAfterAttempt(innsendingsId = key, attempts = attemptsToFail)

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount attemptsToFail + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(attemptsToFail + 1, safUrl)
		verifyMockedPostRequests(attemptsToFail, journalPostUrl)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount attemptsToFail,
				"send files to archive" hasCount 0,
			)
		)
		verifyArchivingMetrics(
			tasksGivenUpOnBefore + 0,
			{ metrics.getTasksGivenUpOn() },
			"Should not have given up on any task"
		)
	}


	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorkingButGivesInvalidResponse()
		mockSafRequest_notFound(innsendingsId = key)

		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		putDataOnKafkaTopic(loggedInMsg)

		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMockedPostRequests(maxNumberOfAttempts, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount maxNumberOfAttempts,
				"send files to archive" hasCount 0,
			)
		)
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		sleep(1000) // Får av og til feil i telling av metrics når alle testene kjøres da metrics endringer i andre tester kan påvirke denne
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val attemptsToFail = 3
		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(attemptsToFail)
		mockSafRequest_notFound(innsendingsId = key)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount attemptsToFail + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)
		verifyMockedPostRequests(attemptsToFail + 1, safUrl)
		verifyMockedPostRequests(attemptsToFail + 1, journalPostUrl)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount attemptsToFail))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount attemptsToFail + 1,
				"send files to archive" hasCount 1,
			)
		)
		verifyArchivingMetrics(
			tasksGivenUpOnBefore + 0,
			{ metrics.getTasksGivenUpOn() },
			"Should not have given up on any task"
		)
	}


	@Test
	fun `Application already archived will cause finishing archiving`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockAlreadyArchivedResponse(1)
		mockSafRequest_notFound(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyMessageStartsWith(key, mapOf("**Archiving: OK" hasCount 1, "Exception" hasCount 1))
		verifyArkiveringstilbakemeldingStartsWith(key, mapOf("**Archiving: OK" hasCount 1))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 1,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Application found after calling saf will cause finishing archiving`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockAlreadyArchivedResponse(1)
		mockSafRequest_found(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1, STARTED hasCount 1, ARCHIVED hasCount 1, FINISHED hasCount 1, FAILURE hasCount 0
			)
		)
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsDown()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMessageStartsWith(key, mapOf("Exception" hasCount maxNumberOfAttempts))
		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)

		verifyArchivingMetrics(getFilestorageErrorsBefore + maxNumberOfAttempts, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		//verifyArchivingMetrics(tasksBefore + 1, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Not all files fetched from Filestorage will cause failure`() {
		sleep(1000) // Får av og til feil i telling av metrics når alle testene kjøres da metrics endringer i andre tester kan påvirke denne
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockRequestedFileIsNotFound()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount tasksBefore.toInt() + 1,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		verifyMessageStartsWith(key, mapOf("ok" hasCount 0, "Exception" hasCount 6))

		verifyKafkaMetric(
			key, mapOf(
				"get files from filestorage" hasCount 0,
				"send files to archive" hasCount 0,
			)
		)

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


	@Test
	fun `Failing to archive and failing to send feedback to innsender`() {
		// Given
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsDown()
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)
		doThrow(RuntimeException("Failed to send arkiveringstilbakemelding")).whenever(kafkaPublisher).putArkiveringstilbakemeldingOnTopic(any(), any(), any())
		doNothing().whenever(kafkaPublisher).putMessageOnTopic(
			eq(key), eq("**Archiving: FAILED."), any())

		// When
		putDataOnKafkaTopic(loggedInMsg)

		// Expect
		verifyProcessingEvents(
			key, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount  maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)
		sleep(8000)
		verify(archiverService, times(maxNumberOfAttempts)).createArkiveringstilbakemelding(eq(key), any())

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
			{
				assertEquals(
					expectedCount, seenEventTypes.invoke(),
					"Expected to see $expectedCount $expectedEventType"
				)
			}
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
			{
				assertEquals(
					expectedCount, seenMessages.invoke(),
					"Expected to see $expectedCount messages starting with '$expectedMessage'"
				)
			}
		}
	}

	private fun verifyArkiveringstilbakemeldingStartsWith(key: Key, messageAndCount: Map<String, Int>) {
		messageAndCount.forEach { (expectedMessage: String, expectedCount: Int) ->

			val seenArkiveringstilbakemeldinger = {
				kafkaListener.getArkiveringstilbakemeldinger()
					.filter { it.key == key }
					.filter { it.value.startsWith(expectedMessage) }
					.size
			}

			loopAndVerify(expectedCount, seenArkiveringstilbakemeldinger)
			{
				assertEquals(
					expectedCount, seenArkiveringstilbakemeldinger.invoke(),
					"Expected to see $expectedCount arkiveringstilbakemeldinger starting with '$expectedMessage'"
				)
			}
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


	private fun verifyRequestDataToJoark(soknadsarkivschema: InnsendingTopicMsg, requestData: OpprettJournalpostRequest) {
		val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

		val (mainDocumentTitle, brevkode) = getTitleAndBrevkode(soknadsarkivschema)

		val expected = OpprettJournalpostRequest(
			AvsenderMottaker(soknadsarkivschema.avsenderDto.id, idType = soknadsarkivschema.avsenderDto.idType?.name, navn = soknadsarkivschema.avsenderDto.navn),
			bruker = if (soknadsarkivschema.brukerDto != null) Bruker(id=soknadsarkivschema.brukerDto?.id!!, idType = soknadsarkivschema.brukerDto?.idType?.name!!) else null,
			datoMottatt = soknadsarkivschema.innsendtDato.format(formatter),
			dokumenter = soknadsarkivschema.dokumenter.mapIndexed{ index, doc ->
				Dokument(
					tittel=if (index == 0) mainDocumentTitle else doc.tittel, brevkode=if (index == 0) brevkode else doc.skjemanummer, dokumentKategori="SOK",
					dokumentvarianter = doc.varianter.map{variant ->
						DokumentVariant(filnavn=variant.filnavn, filtype=variant.filtype, fysiskDokument = filestorageContent.toByteArray(), variantformat = variant.variantFormat!!)
					}
				)},
			eksternReferanseId = soknadsarkivschema.innsendingsId,
			journalpostType = "INNGAAENDE",
			kanal = soknadsarkivschema.kanal,
			tema = soknadsarkivschema.arkivtema,
			tittel = mainDocumentTitle
		)
		assertEquals(expected, requestData)
	}

	private fun getTitleAndBrevkode(soknadsarkivschema: InnsendingTopicMsg): Pair<String, String> {
		if (soknadsarkivschema.ettersendelseTilId != null) {
			return Pair("Ettersendelse til " + soknadsarkivschema.tittel.replaceFirst(soknadsarkivschema.tittel[0], soknadsarkivschema.tittel[0].lowercaseChar()),
				soknadsarkivschema.dokumenter.firstOrNull()?.skjemanummer?.replace("NAV ", "NAVe ")?: "")
		} else {
			return Pair(soknadsarkivschema.tittel,	soknadsarkivschema.dokumenter.firstOrNull()?.skjemanummer ?: "")
		}
	}

	private fun putDataOnKafkaTopic(message: InnsendingTopicMsg) {
		when (message.kanal) {
				"NAV_NO" -> {
					putDataOnTopic(
						key = message.innsendingsId,
						value = objectMapper.writeValueAsString(message),
						headers = RecordHeaders(),
						topic = kafkaConfig.topics.loggedinSubmissionTopic,
						kafkaProducer = kafkaloggedinTopicProducer
					)
				}
				"NAV_NO_UINNLOGGET" -> {
					putDataOnTopic(
						key = message.innsendingsId,
						value = objectMapper.writeValueAsString(message),
						headers = RecordHeaders(),
						topic = kafkaConfig.topics.nologinSubmissionTopic,
						kafkaProducer = kafkaNologinTopicProducer
					)
				}
				else -> {
					throw RuntimeException("Ukjent kanal: ${message.kanal}")
				}
		}
	}


	private fun putDataOnKafkaTopic(key: Key, badData: String, headers: Headers = RecordHeaders()) {
		val topic = kafkaConfig.topics.loggedinSubmissionTopic
		putDataOnTopic(key, badData, headers, topic, kafkaProducerForBadData)
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
