package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaSetupTest
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJson
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJsonSerializer
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventType
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventType.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.util.serializeMsg
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/**
 * Default bootstrap/replay regression suite (issue #266). Exercises only the JSON v3
 * processing-event contract (`processingTopicV3`) and never starts an Avro producer, so the
 * default local/integration test path is genuinely Avro-free (issue #260, user story 12). The one
 * narrowly scoped test that still starts/uses Avro and the mocked Schema Registry - proving replay
 * recovery from retained v2 processing-event history - lives in [LegacyAvroReplayTest] instead.
 * Do not add Avro producers or `EventTypes.*`/`ProcessingEvent` (Avro) usage back into this class.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering")
class StateRecreationTests : ContainerizedKafka() {

	@MockitoBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String

	@Value("\${saf.path}")
	private lateinit var safUrl: String

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var kafkaStreams: KafkaStreams // Mock this so that the real chain isn't run by the tests

	@Autowired
	private lateinit var kafkaConfig: KafkaConfig

	@Autowired
	private lateinit var kafkaPublisher: KafkaPublisher

	private lateinit var kafkaLoggedinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaNologinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaProcessingEventV3Producer: KafkaProducer<String, ProcessingEventJson>
	private lateinit var kafkaProcessingEventV3PoisonProducer: KafkaProducer<String, String>
	private lateinit var kafkaBootstrapConsumer: KafkaBootstrapConsumer

	@Autowired
	private lateinit var metrics: ArchivingMetrics

	private val safService = mockk<SafServiceInterface>()
	private val scheduler = mockk<Scheduler>().also {
		every { it.schedule(any(), any()) } just Runs
		every { it.scheduleSingleTask(any(), any()) } just Runs
	}
	private val archiverService = mockk<ArchiverService>().also {
		every { runBlocking { it.fetchFiles(any(), any()) } } returns listOf(
			FileInfo(
				"id",
				"content".toByteArray(),
				ResponseStatus.Ok
			)
		)
		every { it.archive(any(), any(), any()) } just Runs
	}
	private val taskListService = mockk<TaskListService>().also {
		every { it.addOrUpdateTask(any(), any(), any(), any()) } just Runs
		every { it.clearLoggedTaskStates() } just Runs
	}

	private lateinit var kafkaSetup: KafkaSetupTest

	private val loggedinSoknad = InnsendingTopicMsgBuilder().build()
	private val notLoggedinSoknad = InnsendingTopicMsgBuilder().withKanal("NAV_NO_UINNLOGGET").build()

	private val fileUuid = UUID.randomUUID().toString()

	private val applications = mutableMapOf<String, InnsendingTopicMsg>()

	companion object {

		@JvmField
		@RegisterExtension
		val wireMock: WireMockExtension = WireMockExtension.newInstance()
			.configureStaticDsl(true)
			.options(
				wireMockConfig()
					.port(2902)
					.notifier(ConsoleNotifier(true))
					.withRootDirectory("src/test/resources")
					.asynchronousResponseEnabled(false)
			)
			.build()

		@JvmStatic
		@DynamicPropertySource
		fun properties(reg: DynamicPropertyRegistry) {
			reg.add("innsendingsapi.path") { "/innsendte/v1/files/[0-9a-fA-F-]{36}" }
			reg.add("joark.journal-post") { "/rest/journalpostapi/v1/journalpost" }
			reg.add("saf.path") { "/graphql" }
		}
	}

	@AfterEach
	fun tearDown() {
		wireMock.resetAll()
		kafkaLoggedinTopicProducer.close()
		kafkaNologinTopicProducer.close()
		kafkaProcessingEventV3Producer.close()
		kafkaProcessingEventV3PoisonProducer.close()
		metrics.unregister()
		taskListService.clearLoggedTaskStates()
		// TaskListService.tryToArchive dispatches archiving via CoroutineScope(Dispatchers.Default).launch
		// (fire-and-forget), so a test's archiving work can still be in flight when the test method
		// returns. Since archiverService/taskListService are shared mockk instances across all tests in
		// this class (@TestInstance(PER_CLASS)), clear their recorded call history (but keep the
		// `every { ... }` stubs, hence answers = false) between tests so a straggling call from one test
		// can't be mistaken for - or otherwise pollute the diagnostics of - a subsequent test's
		// verify(...) on the same mocks.
		clearMocks(archiverService, taskListService, answers = false)
	}

	@BeforeEach
	fun setup() {
		wireMock.resetAll()
		setupMockedNetworkServices(
			wireMock,
			portToExternalServices!! + 1,
			journalPostUrl,
			"/innsendte/v1/files",
			safUrl
		)
		kafkaLoggedinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap(kafkaConfig)
			.also {it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java})
		kafkaNologinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap(kafkaConfig)
			.also {it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java})
		kafkaProcessingEventV3Producer = KafkaProducer<String, ProcessingEventJson>(kafkaConfigMap(kafkaConfig)
			.also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ProcessingEventJsonSerializer::class.java })
		kafkaProcessingEventV3PoisonProducer = KafkaProducer<String, String>(kafkaConfigMap(kafkaConfig)
			.also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })
		kafkaBootstrapConsumer = KafkaBootstrapConsumer(taskListService, kafkaConfig)
		kafkaSetup = KafkaSetupTest(
			applicationState = ApplicationState(alive = true, ready = true),
			taskListService = taskListService,
			kafkaPublisher = kafkaPublisher,
			metrics = metrics,
			kafkaConfig = kafkaConfig
		)

		kafkaBootstrapConsumer.recreateState() // Other test classes could have left Kafka events on the topics. Consume them before running the tests in this class.

	}

	@Test
	fun `Can read empty Event Log`() {
		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was never started`() {
		val key = UUID.randomUUID().toString()

		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		publishLoggedinMessage(key)
		publishProcessingEventsV3(key to RECEIVED)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key)
	}


	@Test
	fun `Can read new Msg from not logged in user that was never started`() {
		val key = UUID.randomUUID().toString()

		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		publishNoLoginMessage(key)

		publishProcessingEventsV3(key to RECEIVED)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key)
	}

	// Note: the pure v2-Avro-only replay scenarios, and the dual v2 Avro + v3 JSON history-merge
	// scenarios, are deliberately NOT tested here. Issue #266 confines all Avro/Schema-Registry test
	// producer setup to the single, isolated `LegacyAvroReplayTest`, so that this default test class
	// never starts an Avro producer and the default test path is genuinely Avro-free. See
	// LegacyAvroReplayTest for that coverage.

	@Test
	fun `Replaying a pending v3 JSON processing event resumes archiving`() {
		val key = UUID.randomUUID().toString()

		runBootstrappedArchiveTask()
		val archived = archiveLatch(key)
		publishLoggedinMessage(key)
		publishProcessingEventsV3(key to ProcessingEventType.RECEIVED, key to ProcessingEventType.STARTED)

		KafkaBootstrapConsumer(replayingTaskListService(key), kafkaConfig).recreateState()

		assertTrue(archived.await(10, TimeUnit.SECONDS), "Expected archiverService.archive(key=$key) within 10s")
		verify(exactly = 1) { archiverService.archive(eq(key), any(), any()) }
	}

	@Test
	fun `Replaying a finished v3 JSON processing event creates no archive work`() {
		val key = UUID.randomUUID().toString()

		runBootstrappedArchiveTask()
		publishLoggedinMessage(key)
		publishProcessingEventsV3(
			key to ProcessingEventType.RECEIVED,
			key to ProcessingEventType.STARTED,
			key to ProcessingEventType.ARCHIVED,
			key to ProcessingEventType.FINISHED
		)

		KafkaBootstrapConsumer(replayingTaskListService(key), kafkaConfig).recreateState()

		verify(exactly = 0) { archiverService.archive(eq(key), any(), any()) }
	}

	@Test
	fun `Malformed v3 JSON processing event is dropped, valid v3 events for other keys still replay`() {
		val poisonKey = UUID.randomUUID().toString()
		val firstValidKey = UUID.randomUUID().toString()
		val secondValidKey = UUID.randomUUID().toString()

		runBootstrappedArchiveTask()
		val archived = archiveLatch(firstValidKey, secondValidKey)
		publishLoggedinMessage(poisonKey, firstValidKey, secondValidKey)
		putDataOnTopic(
			poisonKey, "this is not deserializable", RecordHeaders(),
			kafkaConfig.topics.processingTopicV3, kafkaProcessingEventV3PoisonProducer
		)
		publishProcessingEventsV3(firstValidKey to ProcessingEventType.RECEIVED, firstValidKey to ProcessingEventType.STARTED)
		publishProcessingEventsV3(secondValidKey to ProcessingEventType.RECEIVED, secondValidKey to ProcessingEventType.STARTED)

		val replayingBothKeys = replayingTaskListService(firstValidKey, secondValidKey)
		KafkaBootstrapConsumer(replayingBothKeys, kafkaConfig).recreateState()

		assertTrue(
			archived.await(10, TimeUnit.SECONDS),
			"Expected archiverService.archive(...) for both firstValidKey=$firstValidKey and secondValidKey=$secondValidKey within 10s"
		)
		verify(exactly = 1) { archiverService.archive(eq(firstValidKey), any(), any()) }
		verify(exactly = 1) { archiverService.archive(eq(secondValidKey), any(), any()) }
		verify(exactly = 0) { archiverService.archive(eq(poisonKey), any(), any()) }
	}

	@Test
	fun `Can read Event Log with Event that was started once`() {
		val key = UUID.randomUUID().toString()

		publishLoggedinMessage(key)
		publishProcessingEventsV3(
			key to RECEIVED,
			key to STARTED
		)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key)
	}

	@Test
	fun `Can read both Logged in and not Logged in Msgs with Events that was started once`() {
		val key = UUID.randomUUID().toString()
		val key2 = UUID.randomUUID().toString()

		publishLoggedinMessage(key)
		publishNoLoginMessage(key2)
		publishProcessingEventsV3(
			key to RECEIVED,
			key to STARTED,
			key2 to RECEIVED,
			key2 to STARTED
		)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key)
		verifyThatTaskListService().wasCalled(1).forKey(key2)
	}

	@Test
	fun `Can read Event Log with Finished Event - will not reattempt`() {
		val key = UUID.randomUUID().toString()

		publishLoggedinMessage(key)
		publishProcessingEventsV3(
			key to RECEIVED,
			key to STARTED,
			key to ARCHIVED,
			key to FINISHED
		)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with two Events that were started once`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishLoggedinMessage(key0, key1)
		publishProcessingEventsV3(
			key0 to RECEIVED,
			key0 to STARTED,

			key1 to RECEIVED,
			key1 to STARTED
		)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key0)
		verifyThatTaskListService().wasCalled(1).forKey(key1)
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished`() {
		val key = UUID.randomUUID().toString()

		publishLoggedinMessage(key)
		publishProcessingEventsV3(
			key to RECEIVED,
			key to STARTED,
			key to STARTED,
			key to ARCHIVED,
			key to FINISHED
		)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished, but in wrong order`() {
		val key = UUID.randomUUID().toString()

		publishLoggedinMessage(key)
		publishProcessingEventsV3(
			key to RECEIVED,
			key to STARTED,
			key to ARCHIVED,
			key to FINISHED,
			key to STARTED
		)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with one Started and one Finished Event`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishLoggedinMessage(key0, key1)
		publishProcessingEventsV3(
			key0 to RECEIVED,
			key0 to STARTED,

			key1 to RECEIVED,
			key1 to STARTED,
			key1 to ARCHIVED,
			key1 to FINISHED
		)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key0)
		verifyThatTaskListService().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log with mixed order of events`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		val key2 = UUID.randomUUID().toString()

		publishLoggedinMessage(key0, key1, key2)
		publishProcessingEventsV3(
			key1 to RECEIVED,
			key0 to RECEIVED,
			key1 to STARTED,
			key2 to RECEIVED,
			key0 to STARTED,
			key2 to STARTED,
			key1 to ARCHIVED,
			key0 to ARCHIVED,
			key1 to FINISHED,
			key0 to FAILURE
		)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key2)
		verifyThatTaskListService().wasNotCalledForKey(key0)
		verifyThatTaskListService().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log where soknadsarkivschema is missing`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEventsV3(key to RECEIVED, key to STARTED)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Process events, then another event comes in - only the first ones cause scheduling`() {
		val key = UUID.randomUUID().toString()

		publishLoggedinMessage(key)
		publishProcessingEventsV3(
			key to RECEIVED,
			key to STARTED
		)

		recreateState()

		publishProcessingEventsV3(key to STARTED)

		verifyThatTaskListService().wasCalled(1).forKey(key)
	}

	@Test
	fun `Process events, simulate upstart with all received and archived events - none should be scheduled`() {
		val size = 50
		val keyList = MutableList(size) { UUID.randomUUID().toString() }

		keyList.forEach { key -> publishLoggedinMessage(key) }

		keyList.forEach { key ->
			publishProcessingEventsV3(
				key to RECEIVED,
				key to STARTED,
				key to ARCHIVED,
				key to FINISHED
			)
		}

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Process events, simulate upstart with some FINISHED and FAILURE events - none should be scheduled`() {
		val size = 40
		val keyList = MutableList(size) { UUID.randomUUID().toString() }

		keyList.forEach { key -> publishLoggedinMessage(key) }

		keyList.forEach { key ->
			publishProcessingEventsV3(
				key to RECEIVED,
				key to STARTED,
				key to ARCHIVED,
				randomFailureOrFinished(key)
			)
		}

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	var countFinishedOrFailure: Int = 0

	@Test
	fun `Process events, simulate upstart with some FINISHED and FAILURE and Not Finished events - some should be scheduled`() {
		val size = 40
		val keyList = MutableList(size) { UUID.randomUUID().toString() }

		keyList.forEach { key -> publishLoggedinMessage(key) }

		countFinishedOrFailure = 0
		keyList.forEach { key ->
			publishProcessingEventsV3(
				key to RECEIVED,
				key to STARTED,
				randomFailureOrFinishedOrStarted(key)
			)
		}

		recreateState()

		verifyThatTaskListService().wasCalled(size - countFinishedOrFailure)
	}


	private fun randomFailureOrFinished(key: String): Pair<String, ProcessingEventType> {
		val rand = (1..1000).random()
		return if (rand > 600)
			key to FAILURE
		else
			key to FINISHED
	}

	private fun randomFailureOrFinishedOrStarted(key: String): Pair<String, ProcessingEventType> {
		val rand = (1..1000).random()
		if (rand > 300) countFinishedOrFailure + 1 else countFinishedOrFailure
		return if (rand > 400)
			key to FAILURE
		else if (rand > 300)
			key to FINISHED
		else
			key to STARTED
	}

	private fun publishLoggedinMessage(vararg keys: String) {
		keys.forEach {
			applications.put(it, loggedinSoknad.copy(innsendingsId = it, kanal = "NAV_NO"))
			val topic = kafkaConfig.topics.loggedinSubmissionTopic
			putDataOnTopic(it,  serializeMsg( applications[it]!!), RecordHeaders(), topic, kafkaLoggedinTopicProducer)
		}
	}

	private fun publishNoLoginMessage(vararg keys: String) {
		keys.forEach {
			applications.put(it, notLoggedinSoknad.copy(innsendingsId = it, kanal = "NAV_NO_UINNLOGGET"))
			val topic = kafkaConfig.topics.nologinSubmissionTopic
			putDataOnTopic(it,  serializeMsg( notLoggedinSoknad.copy(innsendingsId = it)), RecordHeaders(), topic, kafkaNologinTopicProducer)
		}
	}

	private fun publishProcessingEventsV3(vararg keysAndEventTypes: Pair<String, ProcessingEventType>) {
		keysAndEventTypes.forEach { (key, eventType) ->
			val topic = kafkaConfig.topics.processingTopicV3
			putDataOnTopic(key, ProcessingEventJson(eventType), RecordHeaders(), topic, kafkaProcessingEventV3Producer)
		}
	}

	private fun recreateState() {
		kafkaBootstrapConsumer.recreateState()
	}

	private fun replayingTaskListService(vararg keysToReplay: String) = object : TaskListService(
		archiverService,
		safService,
		0,
		listOf(0),
		ApplicationState(alive = true, ready = true),
		scheduler,
		metrics,
		kafkaPublisher
	) {
		override fun addOrUpdateTask(
			key: String,
			soknadarkivschema: InnsendingTopicMsg,
			state: EventTypes,
			isBootstrappingTask: Boolean
		) {
			if (keysToReplay.contains(key)) {
				super.addOrUpdateTask(key, soknadarkivschema, state, isBootstrappingTask)
			}
		}
	}

	private fun runBootstrappedArchiveTask() {
		val task = slot<() -> Unit>()
		every { safService.hentJournalpostGittInnsendingId(any()) } returns null
		every { scheduler.scheduleSingleTask(capture(task), any()) } answers { task.captured.invoke() }
	}

	// archiverService.archive(...) runs on TaskListService's fire-and-forget
	// CoroutineScope(Dispatchers.Default).launch { ... } (see TaskListService.tryToArchive), so tests
	// can't assert on it synchronously right after recreateState() returns. Rather than polling for it
	// with MockK's verify(timeout = ...) - which races against, and can itself add CPU pressure that
	// competes with, that same coroutine, making failures worse the longer the timeout is set to - each
	// key gets its own CountDownLatch that is counted down the instant archive() is actually invoked for
	// that key. This is a one-shot, event-driven wait: it returns as soon as the call happens rather than
	// on a polling cadence, and - since the latch is a fresh local object per test - it can't be confused
	// by a leftover call from a previous test the way shared-mock call-history-based verification can.
	private fun archiveLatch(vararg keys: String): CountDownLatch {
		val latch = CountDownLatch(keys.size)
		keys.forEach { key ->
			every { archiverService.archive(eq(key), any(), any()) } answers { latch.countDown() }
		}
		return latch
	}


	private fun verifyThatTaskListService() = TaskListServiceVerifier()

	private inner class TaskListServiceVerifier {
		private var timesCalled = 0
		private var key: String? = null

		fun wasCalled(times: Int): KeyStep {
			timesCalled = times
			return KeyStep()
		}

		fun wasNotCalled() {
			verify()
		}

		fun wasNotCalledForKey(key: String) {
			this.key = key
			verify()
		}

		inner class KeyStep {
			fun forKey(theKey: String) {
				key = theKey
				verify()
			}
		}

		private fun verify() {
			val key = this.key
			val application = applications[key] ?: loggedinSoknad

			if (key == null || timesCalled == 0)
				verify(atLeast = timesCalled) { taskListService.addOrUpdateTask(any(), any(), any(), any()) }
			else
				verify(atLeast = timesCalled) { taskListService.addOrUpdateTask(eq(key), eq(application), any(), any()) }
		}
	}

}
