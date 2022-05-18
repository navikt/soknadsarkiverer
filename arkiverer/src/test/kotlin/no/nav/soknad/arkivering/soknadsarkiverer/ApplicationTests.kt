package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
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
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@Import(ContainerizedKafka::class)
class ApplicationTests {

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

		kafkaListener = KafkaListener(appConfiguration.kafkaConfig)
	}

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(
			portToExternalServices!!,
			appConfiguration.config.joarkUrl,
			appConfiguration.config.filestorageUrl
		)

		maxNumberOfAttempts = appConfiguration.config.retryTime.size
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
		val soknadsarkivschema = createSoknadarkivschema()

		putDataOnKafkaTopic(key, soknadsarkivschema)

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 1, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1, "Exception" to 0))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 1,
			"send files to archive" to 1,
			"delete files from filestorage" to 1
		))
		val requests = verifyPostRequest(appConfiguration.config.joarkUrl)
		assertEquals(1, requests.size)
		val request = objectMapper.readValue<OpprettJournalpostRequest>(requests[0].body)
		verifyRequestDataToJoark(soknadsarkivschema, request)
	}

	@Test
	fun `Sending in invalid data will not create Processing Events`() {
		val key = UUID.randomUUID().toString()
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(key, invalidData)

		TimeUnit.MILLISECONDS.sleep(500)
		verifyProcessingEvents(key, mapOf(RECEIVED to 0, STARTED to 0, ARCHIVED to 0, FINISHED to 0, FAILURE to 0))
		verifyMockedPostRequests(0, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 0,
			"send files to archive" to 0,
			"delete files from filestorage" to 0
		))
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsDown()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to maxNumberOfAttempts, ARCHIVED to 0, FINISHED to 0, FAILURE to 0))
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(key, mapOf("ok" to 0, "Exception" to maxNumberOfAttempts))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to maxNumberOfAttempts,
			"send files to archive" to 0,
			"delete files from filestorage" to 0
		))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })
	}

	@Test
	fun `Restart task after failing succeeds`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(appConfiguration.config.retryTime.size + 1)
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to maxNumberOfAttempts, ARCHIVED to 0, FINISHED to 0, FAILURE to 1))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 1, { metrics.getTasksGivenUpOn() })

		val failedKeys = taskListService.getFailedTasks()
		assertTrue(failedKeys.contains(key))

		taskListService.startPaNytt(key)

		verifyProcessingEvents(key, mapOf(FINISHED to 1))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() })
	}


	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val key = UUID.randomUUID().toString()
		val keyForPoisonPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(keyForPoisonPill, "this is not deserializable")
		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 1, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 1,
			"send files to archive" to 1,
			"delete files from filestorage" to 1
		))
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(1)

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 2, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1, "Exception" to 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 2,
			"send files to archive" to 1,
			"delete files from filestorage" to 1
		))

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 2, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 1, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 1, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 1, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore + 0, { metrics.getTasks() }, "Should have created and finished task")
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() }, "Should not have given up on any task")
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		val key = UUID.randomUUID().toString()
		val attemptsToFail = 3
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 4, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyMockedPostRequests(attemptsToFail + 1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1, "Exception" to attemptsToFail))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 4,
			"send files to archive" to 1,
			"delete files from filestorage" to 1
		))
		verifyArchivingMetrics(tasksGivenUpOnBefore + 0, { metrics.getTasksGivenUpOn() }, "Should not have given up on any task")
	}

	@Test
	fun `Everything works, but Filestorage cannot delete files -- Message is nevertheless marked as finished`() {
		val key = UUID.randomUUID().toString()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val delFilestorageErrorsBefore = metrics.getDelFilestorageErrors()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockFilestorageDeletionIsNotWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 1, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1, "Exception" to 0))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 1,
			"send files to archive" to 1,
			"delete files from filestorage" to 1 // Metric succeeds even if the operation fails
		))

		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageErrorsBefore + 1, { metrics.getDelFilestorageErrors() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 1, { metrics.getJoarkSuccesses() })
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorkingButGivesInvalidResponse()

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to maxNumberOfAttempts, ARCHIVED to 0, FINISHED to 0, FAILURE to 1))
		verifyMockedPostRequests(maxNumberOfAttempts, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(key, mapOf("ok" to 0, "Exception" to maxNumberOfAttempts))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to maxNumberOfAttempts,
			"send files to archive" to 0,
			"delete files from filestorage" to 0
		))
	}


	@Test
	fun `Application already archived will cause finishing archiving`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsWorking(fileUuid)
		mockAlreadyArchivedResponse(1)

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 1, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1, "Exception" to 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 1,
			"send files to archive" to 0,
			"delete files from filestorage" to 1
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + 0, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 1, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 1, { metrics.getDelFilestorageSuccesses() })
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

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to maxNumberOfAttempts, ARCHIVED to 0, FINISHED to 0, FAILURE to 1))
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(key, mapOf("ok" to 0, "Exception" to maxNumberOfAttempts))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 0,
			"send files to archive" to 0,
			"delete files from filestorage" to 0
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
	fun `Files deleted from Filestorage will cause finishing archiving`() {
		val key = UUID.randomUUID().toString()
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockRequestedFileIsGone()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(key, createSoknadarkivschema())

		verifyProcessingEvents(key, mapOf(RECEIVED to 1, STARTED to 1, ARCHIVED to 1, FINISHED to 1, FAILURE to 0))
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(key, mapOf("ok" to 1, "Exception" to 1))
		verifyKafkaMetric(key, mapOf(
			"get files from filestorage" to 0,
			"send files to archive" to 0,
			"delete files from filestorage" to 1
		))

		verifyArchivingMetrics(getFilestorageErrorsBefore + 1, { metrics.getGetFilestorageErrors() })
		verifyArchivingMetrics(getFilestorageSuccessesBefore + 0, { metrics.getGetFilestorageSuccesses() })
		verifyArchivingMetrics(delFilestorageSuccessesBefore + 0, { metrics.getDelFilestorageSuccesses() })
		verifyArchivingMetrics(joarkErrorsBefore + 0, { metrics.getJoarkErrors() })
		verifyArchivingMetrics(joarkSuccessesBefore + 0, { metrics.getJoarkSuccesses() })
		verifyArchivingMetrics(tasksBefore, { metrics.getTasks() })
		verifyArchivingMetrics(tasksGivenUpOnBefore, { metrics.getTasksGivenUpOn() })
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


	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, appConfiguration.config.filestorageUrl + ".*")
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


	private fun putDataOnKafkaTopic(key: Key, soknadarkivschema: Soknadarkivschema, headers: Headers = RecordHeaders()) {
		val topic = appConfiguration.kafkaConfig.inputTopic
		putDataOnTopic(key, soknadarkivschema, headers, topic, kafkaProducer)
	}

	private fun putDataOnKafkaTopic(key: Key, badData: String, headers: Headers = RecordHeaders()) {
		val topic = appConfiguration.kafkaConfig.inputTopic
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
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.kafkaBrokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
