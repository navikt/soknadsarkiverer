package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.Semaphore

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
		assertEquals(0, taskListService.listTasks()[key]!!.isRunningLock.availablePermits())

		verifyTaskIsRunning(key)
	}

	@Test
	fun `Can update task`() {
		val originalCount = 0
		val newCount = 2
		val key = UUID.randomUUID().toString()

		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), originalCount)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(originalCount, taskListService.listTasks()[key]!!.count)

		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), newCount)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(newCount, taskListService.listTasks()[key]!!.count)

		// Try to update back to original count, but that value wont be saved
		taskListService.addOrUpdateTask(key, createSoknadarkivschema(), originalCount)
		assertEquals(1, taskListService.listTasks().size)
		assertEquals(newCount, taskListService.listTasks()[key]!!.count)
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

		taskListService.listTasks()[key] = Task(createSoknadarkivschema(), 0, LocalDateTime.now(), Semaphore(1).also { it.acquire() })
		assertFalse(taskListService.listTasks().isEmpty())

		taskListService.tryToArchive(key, createSoknadarkivschema())

		assertTrue(taskListService.listTasks().isEmpty())
		verify(archiverService, times(1)).archive(eq(key), any())
		verify(scheduler, times(0)).schedule(any(), any<Instant>())
	}

	@Test
	fun `Archiving does not succeed - will not remove task from list but attempt to schedule again`() {
		val count = 0
		val key = UUID.randomUUID().toString()
		whenever(archiverService.archive(eq(key), any())).thenThrow(RuntimeException("Mocked exception"))

		taskListService.listTasks()[key] = Task(createSoknadarkivschema(), 0, LocalDateTime.now(), Semaphore(1).also { it.acquire() })
		assertFalse(taskListService.listTasks().isEmpty())

		taskListService.tryToArchive(key, createSoknadarkivschema())

		assertFalse(taskListService.listTasks().isEmpty())
		assertEquals(count + 1, taskListService.listTasks()[key]!!.count)
		verify(archiverService, times(1)).archive(eq(key), any())
		verify(scheduler, times(1)).schedule(any(), any<Instant>())
	}


	private fun verifyTaskIsRunning(key: String) {
		val getCount = {
			taskListService.listTasks()
				.filter { it.key == key && it.value.isRunningLock.availablePermits() == 0 }
				.size
		}
		loopAndVerify(1, getCount)
	}
}
