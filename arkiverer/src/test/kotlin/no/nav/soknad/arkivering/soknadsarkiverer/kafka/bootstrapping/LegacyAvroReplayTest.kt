package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.runBlocking
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJson
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventJsonSerializer
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.ProcessingEventType
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.util.serializeMsg
import no.nav.soknad.arkivering.soknadsarkiverer.utils.ContainerizedKafka
import no.nav.soknad.arkivering.soknadsarkiverer.utils.InnsendingTopicMsgBuilder
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #266: the one narrowly scoped legacy test that still starts/uses Avro and the (mocked)
 * Schema Registry. Every other bootstrap/replay test in this package
 * (see [StateRecreationTests]) exercises only the JSON v3 processing-event contract and never
 * touches an Avro producer, so that the default local/integration test path is genuinely
 * Avro-free (issue #260, user story 12). This class exists solely to keep proving user story 13:
 * that replay recovery from *retained* v2 Avro processing-event history keeps working, so a
 * later, separate Avro-retirement change cannot silently break it. All Avro/Schema-Registry test
 * producer setup is confined to this file - do not add Avro producers to [StateRecreationTests].
 *
 * Deliberately kept to a single, consolidated test method rather than one test per scenario:
 * that is what makes this "one legacy test" in the sense of issue #266, while still covering both
 * the pure v2-only baseline (issue #261) and the dual v2+v3 history merge (issue #264).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class, KafkaConfig::class)
class LegacyAvroReplayTest : ContainerizedKafka() {

	@MockBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var kafkaStreams: KafkaStreams // Mock this so that the real chain isn't run by the tests

	@Autowired
	private lateinit var kafkaConfig: KafkaConfig

	@Autowired
	private lateinit var kafkaPublisher: KafkaPublisher

	@Autowired
	private lateinit var metrics: ArchivingMetrics

	private lateinit var kafkaLoggedinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaProcessingEventProducer: KafkaProducer<String, ProcessingEvent>
	private lateinit var kafkaProcessingEventV3Producer: KafkaProducer<String, ProcessingEventJson>

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

	private val loggedinSoknad = InnsendingTopicMsgBuilder().build()
	private val applications = mutableMapOf<String, InnsendingTopicMsg>()

	@BeforeAll
	fun setup() {
		kafkaLoggedinTopicProducer = KafkaProducer<String, String>(
			kafkaConfigMap(kafkaConfig).also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java }
		)
		kafkaProcessingEventProducer = KafkaProducer<String, ProcessingEvent>(kafkaConfigMap(kafkaConfig))
		kafkaProcessingEventV3Producer = KafkaProducer<String, ProcessingEventJson>(
			kafkaConfigMap(kafkaConfig).also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ProcessingEventJsonSerializer::class.java }
		)

		// Other test classes could have left Kafka events on the topics. Consume them before running
		// the test in this class.
		KafkaBootstrapConsumer(mockk<TaskListService>(relaxed = true), kafkaConfig).recreateState()
	}

	@Test
	fun `Replaying retained v2 Avro processing-event history resumes pending work, skips finished work, and merges correctly with v3 JSON history`() {
		val pendingV2Key = UUID.randomUUID().toString()
		val finishedV2Key = UUID.randomUUID().toString()
		val pendingMergedKey = UUID.randomUUID().toString()
		val finishedMergedKey = UUID.randomUUID().toString()

		every { safService.hentJournalpostGittInnsendingId(any()) } returns null
		val task = slot<() -> Unit>()
		every { scheduler.scheduleSingleTask(capture(task), any()) } answers { task.captured.invoke() }

		val archived = CountDownLatch(2) // pendingV2Key and pendingMergedKey are expected to archive
		listOf(pendingV2Key, pendingMergedKey).forEach { key ->
			every { archiverService.archive(eq(key), any(), any()) } answers { archived.countDown() }
		}

		publishLoggedinMessage(pendingV2Key, finishedV2Key, pendingMergedKey, finishedMergedKey)

		// Pure v2 Avro history (issue #261 baseline): one pending, one finished.
		publishProcessingEvents(pendingV2Key to RECEIVED, pendingV2Key to STARTED)
		publishProcessingEvents(
			finishedV2Key to RECEIVED,
			finishedV2Key to STARTED,
			finishedV2Key to ARCHIVED,
			finishedV2Key to FINISHED
		)

		// Dual v2 Avro + v3 JSON history (issue #264): each task starts out as retained v2 Avro
		// history, then continues as v3 JSON history after the cutover.
		publishProcessingEvents(pendingMergedKey to RECEIVED)
		publishProcessingEventsV3(pendingMergedKey to ProcessingEventType.STARTED)
		publishProcessingEvents(finishedMergedKey to RECEIVED, finishedMergedKey to STARTED)
		publishProcessingEventsV3(
			finishedMergedKey to ProcessingEventType.ARCHIVED,
			finishedMergedKey to ProcessingEventType.FINISHED
		)

		val replayingTaskListService = replayingTaskListService(pendingV2Key, pendingMergedKey)
		KafkaBootstrapConsumer(replayingTaskListService, kafkaConfig).recreateState()

		assertTrue(
			archived.await(10, TimeUnit.SECONDS),
			"Expected archiverService.archive(...) for both pendingV2Key=$pendingV2Key and pendingMergedKey=$pendingMergedKey within 10s"
		)
		verify(exactly = 1) { archiverService.archive(eq(pendingV2Key), any(), any()) }
		verify(exactly = 0) { archiverService.archive(eq(finishedV2Key), any(), any()) }
		verify(exactly = 1) { archiverService.archive(eq(pendingMergedKey), any(), any()) }
		verify(exactly = 0) { archiverService.archive(eq(finishedMergedKey), any(), any()) }
	}

	private fun publishLoggedinMessage(vararg keys: String) {
		keys.forEach {
			applications[it] = loggedinSoknad.copy(innsendingsId = it, kanal = "NAV_NO")
			val topic = kafkaConfig.topics.loggedinSubmissionTopic
			putDataOnTopic(it, serializeMsg(applications[it]!!), RecordHeaders(), topic, kafkaLoggedinTopicProducer)
		}
	}

	private fun publishProcessingEvents(vararg keysAndEventTypes: Pair<String, EventTypes>) {
		keysAndEventTypes.forEach { (key, eventType) ->
			val topic = kafkaConfig.topics.processingTopic
			putDataOnTopic(key, ProcessingEvent(eventType), RecordHeaders(), topic, kafkaProcessingEventProducer)
		}
	}

	private fun publishProcessingEventsV3(vararg keysAndEventTypes: Pair<String, ProcessingEventType>) {
		keysAndEventTypes.forEach { (key, eventType) ->
			val topic = kafkaConfig.topics.processingTopicV3
			putDataOnTopic(key, ProcessingEventJson(eventType), RecordHeaders(), topic, kafkaProcessingEventV3Producer)
		}
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
}
