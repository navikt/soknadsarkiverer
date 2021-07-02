package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import io.prometheus.client.CollectorRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.config.startUpSecondsForTest
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import java.util.*
import java.util.concurrent.TimeUnit

class TaskListServiceTests {

	private val archiverService = mock<ArchiverService>()
	private val scheduler = mock<Scheduler>()
	private val kafkaPublisher = mock<KafkaPublisher>()
	private val metrics = ArchivingMetrics(CollectorRegistry.defaultRegistry)

	private val taskListService = TaskListService(archiverService, AppConfiguration(), scheduler, metrics, kafkaPublisher)

	private val soknadarkivschema = createSoknadarkivschema()

	@BeforeEach
	fun setup() {
		val keyCaptor = argumentCaptor<String>()
		val processingEventCaptor = argumentCaptor<ProcessingEvent>()
		whenever(kafkaPublisher.putProcessingEventOnTopic(capture(keyCaptor), capture(processingEventCaptor), any()))
			.then { taskListService.addOrUpdateTask(keyCaptor.value, soknadarkivschema, processingEventCaptor.value.type) }
	}

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
		TimeUnit.SECONDS.sleep(startUpSecondsForTest + 2)

		verify(archiverService, timeout(10_000).times(1)).archive(eq(key), any(), any())
		verify(archiverService, timeout(10_000).times(1)).deleteFiles(eq(key), any())
		verify(scheduler, timeout(10_000).times(1)).schedule(any(), any())
		loopAndVerify(0, { taskListService.listTasks().size })
	}

	@Test
	fun `Archiving does not succeed - will not remove task from list but attempt to schedule again`() {
		val key = UUID.randomUUID().toString()
		runScheduledTaskOnScheduling()
		whenever(archiverService.archive(eq(key), any(), any())).thenThrow(RuntimeException("Mocked exception"))

		taskListService.addOrUpdateTask(key, soknadarkivschema, EventTypes.RECEIVED)

		loopAndVerify(1, { getTaskListCount(key) })
		assertFalse(taskListService.listTasks().isEmpty())
		verify(archiverService, timeout(10_000).atLeast(1)).archive(eq(key), any(), any())
		verify(scheduler, timeout(10_000).atLeast(2)).schedule(any(), any())
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
		val captor = argumentCaptor<() -> Unit>()
		whenever(scheduler.schedule(capture(captor), any()))
			.then { captor.value.invoke() } // Run scheduled task on first invocation of scheduler.schedule()
			.then { } // Do nothing on second invocation of scheduler.schedule()
	}


	private fun getTaskListCount(key: String) = getTaskListPair(key).first
	private fun getTaskListLock (key: String) = getTaskListPair(key).second
	private fun getTaskListPair (key: String) = taskListService.listTasks()[key] ?: error("Expected to find $key in map")

	private inline fun <reified T> argumentCaptor(): ArgumentCaptor<T> = ArgumentCaptor.forClass(T::class.java)
}
