package no.nav.soknad.arkivering.soknadsarkiverer.service

import io.mockk.*
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class TaskListServiceTests {

	private lateinit var metrics: ArchivingMetrics

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
		every { it.deleteFiles(any(), any()) } just Runs
	}
	private val kafkaPublisher = mockk<KafkaPublisher>().also {
		every { it.putProcessingEventOnTopic(any(), any(), any()) } just Runs
	}

	private val safService = mockk<SafServiceInterface>()

	private val soknadarkivschema = createSoknadarkivschema()

	private lateinit var taskListService: TaskListService

	@BeforeEach
	fun setup() {
		metrics = ArchivingMetrics(PrometheusRegistry.defaultRegistry)
		metrics.unregister()
		val secondsBetweenRetries = listOf(0L, 0L, 0L, 0L, 0L, 0L)
		taskListService = TaskListService(
			archiverService,
			safService,
			0,
			secondsBetweenRetries,
			ApplicationState(),
			scheduler,
			metrics,
			kafkaPublisher
		)
	}


	@Test
	fun `No tasks, can list`() {
		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Can add task`() {
		taskListService.addOrUpdateTask(UUID.randomUUID().toString(), soknadarkivschema, EventTypes.RECEIVED)

		verify(atLeast = 1, timeout = 2_000) {
			kafkaPublisher.putProcessingEventOnTopic(
				any(),
				eq(ProcessingEvent(EventTypes.STARTED)),
				any()
			)
		}
		assertEquals(1, taskListService.listTasks().size)
	}


	@Test
	fun `Can update task`() {
		val originalCount = 0
		val key = UUID.randomUUID().toString()

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(originalCount, getTaskListCount(key))
		verify(atLeast = 1, timeout = 2_000) {
			kafkaPublisher.putProcessingEventOnTopic(
				eq(key),
				eq(ProcessingEvent(EventTypes.STARTED)),
				any()
			)
		}

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.STARTED)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(originalCount, getTaskListCount(key))

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.ARCHIVED)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(originalCount, getTaskListCount(key))
	}

	@Test
	fun `Can finish task`() {
		assertTrue(taskListService.listTasks().isEmpty())

		val key = UUID.randomUUID().toString()
		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)
		assertFalse(taskListService.listTasks().isEmpty())

		taskListService.finishTask(key)
		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Wont crash if non-present task is finished`() {
		assertTrue(taskListService.listTasks().isEmpty())

		taskListService.finishTask(UUID.randomUUID().toString())

		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Archiving succeeds - will trigger ARCHIVED processing event`() {
		val key = UUID.randomUUID().toString()
		runScheduledTaskOnScheduling()

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.STARTED)

		verify(exactly = 1, timeout = 2_000) { archiverService.archive(eq(key), any(), any()) }
		verify(atLeast = 1, timeout = 2_000) {
			kafkaPublisher.putProcessingEventOnTopic(
				eq(key),
				eq(ProcessingEvent(EventTypes.ARCHIVED)),
				any()
			)
		}

	}

	@Test
	fun `Archiving does not succeed - will trigger new STARTED processing event`() {
		val key = UUID.randomUUID().toString()
		runScheduledTaskOnScheduling()
		every { archiverService.archive(eq(key), any(), any()) } throws RuntimeException("Mocked exception")

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.STARTED)

		verify(atLeast = 1, timeout = 2_000) { scheduler.schedule(any(), any()) }
		verify(atLeast = 1, timeout = 2_000) { archiverService.archive(eq(key), any(), any()) }
		verify(atLeast = 1, timeout = 2_000) {
			kafkaPublisher.putProcessingEventOnTopic(
				eq(key),
				eq(ProcessingEvent(EventTypes.STARTED)),
				any()
			)
		}
		assertFalse(taskListService.listTasks().isEmpty())
		loopAndVerify(1, { getTaskListCount(key) })
		assertEquals(1, taskListService.getNumberOfAttempts(key))

	}


	@Test
	fun `Archiving succeeds - after RECEIVED and STARTED processing event`() {
		val key = UUID.randomUUID().toString()
		runScheduledTaskAndContinue(key)

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)
		//taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.STARTED)

		verify(atLeast = 1, timeout = 2_000) {
			kafkaPublisher.putProcessingEventOnTopic(
				eq(key),
				eq(ProcessingEvent(EventTypes.STARTED)),
				any()
			)
		}
		verify(exactly = 1, timeout = 2_000) { archiverService.archive(eq(key), any(), any()) }
		verify(atLeast = 1, timeout = 2_000) {
			kafkaPublisher.putProcessingEventOnTopic(
				eq(key),
				eq(ProcessingEvent(EventTypes.ARCHIVED)),
				any()
			)
		}

	}

	private fun verifyTaskIsRunning(key: String) {
		val getCount = {
			taskListService.listTasks()
				.filter { it.key == key && it.value.second.availablePermits() == 0 }
				.size
		}
		loopAndVerify(1, getCount)
	}

	private fun runScheduledTaskOnScheduling() {
		val captor = slot<() -> Unit>()
		// Run scheduled task on first invocation of scheduler.schedule(), do nothing on subsequent invocations
		every { scheduler.schedule(capture(captor), any()) } answers { captor.captured.invoke() } andThenJust Runs
	}

	private fun runScheduledTaskAndContinue(key: String) {
		val captor = slot<() -> Unit>()
		val processing = slot<ProcessingEvent>()
		// Run scheduled task on first invocation of scheduler.schedule(), do nothing on subsequent invocations
		every { scheduler.schedule(capture(captor), any()) } answers { captor.captured.invoke() } andThenJust Runs
		every {
			kafkaPublisher.putProcessingEventOnTopic(
				eq(key),
				capture(processing),
				any()
			)
		} answers { taskListService.addOrUpdateTask(key, soknadarkivschema, processing.captured.type) } andThenJust Runs
	}


	private fun getTaskListCount(key: String) = getTaskListPair(key).first
	private fun getTaskListLock(key: String) = getTaskListPair(key).second
	private fun getTaskListPair(key: String) = taskListService.listTasks()[key] ?: error("Expected to find $key in map")
}
