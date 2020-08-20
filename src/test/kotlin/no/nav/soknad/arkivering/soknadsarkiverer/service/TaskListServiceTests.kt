package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import java.util.*

class TaskListServiceTests {

	private val archiverService = mock<ArchiverService>()
	private val scheduler = mock<Scheduler>()

	private val taskListService = TaskListService(archiverService, AppConfiguration(), scheduler)

	@Test
	fun `No tasks, can list`() {
		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Can add task`() {
		taskListService.addOrUpdateTask(UUID.randomUUID().toString(), createSoknadarkivschema(), 0)

		assertEquals(1, taskListService.listTasks().size)
	}

	@Test
	fun `Newly added tasks are started after a short delay`() {
		val key = UUID.randomUUID().toString()
		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), 0)
		assertEquals(0, getTaskListLock(key).availablePermits())

		verifyTaskIsRunning(key)
	}

	@Test
	fun `Can update task`() {
		val originalCount = 0
		val newCount = 2
		val key = UUID.randomUUID().toString()

		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), originalCount)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(originalCount, getTaskListCount(key))

		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), newCount)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(newCount, getTaskListCount(key))

		// Try to update back to original count, but that value wont be saved
		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), originalCount)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(newCount, getTaskListCount(key))
	}

	@Test
	fun `Can finish task`() {
		assertTrue(taskListService.listTasks().isEmpty())

		val key = UUID.randomUUID().toString()
		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), 0)
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

		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), 0)

		loopAndVerify(0, { taskListService.listTasks().size })
		verify(archiverService, times(1)).archive(eq(key), any())
		verify(scheduler, times(1)).schedule(any(), any())
	}

	@Test
	fun `Archiving does not succeed - will not remove task from list but attempt to schedule again`() {
		val count = 0
		val key = UUID.randomUUID().toString()
		runScheduledTaskOnScheduling()
		whenever(archiverService.archive(eq(key), any())).thenThrow(RuntimeException("Mocked exception"))

		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), count)

		loopAndVerify(count + 1, { getTaskListCount(key) })
		assertFalse(taskListService.listTasks().isEmpty())
		verify(archiverService, atLeast(1)).archive(eq(key), any())
		verify(scheduler, atLeast(2)).schedule(any(), any())
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
