package no.nav.soknad.arkivering.soknadsarkiverer.service

import io.mockk.*
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime.now
import java.util.*

class TaskListServiceTests {

	private val metrics: ArchivingMetrics = ArchivingMetrics(CollectorRegistry.defaultRegistry)

	private	val scheduler = mockk<Scheduler>()
	private val	archiverService = mockk<ArchiverService>().also {
			every {	runBlocking{it.fetchFiles(any(), any())}} returns listOf(FileInfo("id", "content".toByteArray(), ResponseStatus.Ok))
			every { it.archive(any(), any(), any()) } just Runs
			every { it.deleteFiles(any(), any()) } just Runs
		}
	private val	kafkaPublisher = mockk<KafkaPublisher>().also {
			every { it.putProcessingEventOnTopic(any(), any(), any()) } just Runs
		}


	private val taskListService = TaskListService(
		archiverService,
		0,
		listOf(0, 0, 0, 0, 0, 0),
		ApplicationState(),
		scheduler,
		metrics,
		kafkaPublisher
	)
	private val soknadarkivschema = createSoknadarkivschema()


	@AfterEach
	fun teardown() {
		metrics.unregister()
	}


	@Test
	fun `No tasks, can list`() {
		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Can add task`() {
		taskListService.addOrUpdateTask(UUID.randomUUID().toString(), soknadarkivschema, EventTypes.RECEIVED)

		assertEquals(1, taskListService.listTasks().size)
	}

	@Test
	fun `Newly added tasks are started after a short delay`() {
		val key = UUID.randomUUID().toString()
		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)
		assertEquals(0, getTaskListLock(key).availablePermits())

		verifyTaskIsRunning(key)
	}

	@Test
	fun `Can update task`() {
		val originalCount = 0
		val key = UUID.randomUUID().toString()

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(originalCount, getTaskListCount(key))

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
	fun `Archiving succeeds - will remove task from list and not attempt to schedule again`() {
		val key = UUID.randomUUID().toString()
		runScheduledTaskOnScheduling()

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)

		verify(exactly = 1, timeout = 10_000) { archiverService.archive(eq(key), any(), any()) }
		verify(exactly = 1, timeout = 10_000) { archiverService.deleteFiles(eq(key), any()) }
		verify(exactly = 1, timeout = 10_000) { scheduler.schedule(any(), any()) }
		verify(exactly = 1, timeout = 10_000) { kafkaPublisher.putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.FINISHED)), any()) }
		loopAndVerify(0, { taskListService.listTasks().size })
	}

	@Test
	fun `Archiving does not succeed - will not remove task from list but attempt to schedule again`() {
		val key = UUID.randomUUID().toString()
		runScheduledTaskOnScheduling()
		every { archiverService.archive(eq(key), any(), any()) } throws RuntimeException("Mocked exception")

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)

		loopAndVerify(1, { getTaskListCount(key) })
		assertFalse(taskListService.listTasks().isEmpty())
		verify(atLeast = 1, timeout = 10_000) { archiverService.archive(eq(key), any(), any()) }
		verify(atLeast = 2, timeout = 10_000) { scheduler.schedule(any(), any()) }
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


	private fun getTaskListCount(key: String) = getTaskListPair(key).first
	private fun getTaskListLock (key: String) = getTaskListPair(key).second
	private fun getTaskListPair (key: String) = taskListService.listTasks()[key] ?: error("Expected to find $key in map")
}
