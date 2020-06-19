package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattDokumentBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattVariantBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.SoknadarkivschemaBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import java.util.*

class TaskListServiceTests {

	private val schedulerMock = mock<SchedulerService>()
	private val taskListService = TaskListService(schedulerMock)

	@Test
	fun `Can list task when there are none`() {
		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Can create task - will schedule`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val count = 0

		taskListService.createTask(uuid, value, count)

		val tasks = taskListService.listTasks()
		assertEquals(1, tasks.size)
		assertEquals(value, tasks.fetch(uuid).first)
		assertEquals(count, tasks.fetch(uuid).second)

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(count))
	}

	@Test
	fun `Duplicate creation of tasks discards duplicate`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val countOriginal = 0
		val countUpdated = 71

		taskListService.createTask(uuid, value, countOriginal)
		taskListService.createTask(uuid, value, countUpdated)

		val tasks = taskListService.listTasks()
		assertEquals(1, tasks.size)
		assertEquals(value, tasks.fetch(uuid).first)
		assertEquals(countOriginal, tasks.fetch(uuid).second)

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(countOriginal))
		verify(schedulerMock, times(0)).schedule(eq(uuid), eq(value), eq(countUpdated))
	}

	@Test
	fun `Can update task`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val countOriginal = 1
		val countUpdated = 2

		taskListService.createTask(uuid, value, countOriginal)
		assertEquals(countOriginal, taskListService.listTasks().fetch(uuid).second)

		taskListService.updateTaskCount(uuid, countUpdated)
		assertEquals(countUpdated, taskListService.listTasks().fetch(uuid).second)

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(countOriginal))
		verify(schedulerMock, times(0)).schedule(eq(uuid), eq(value), eq(countUpdated))
	}

	@Test
	fun `Updating non-existent task will not produce exception`() {
		val nonExistentUuid = UUID.randomUUID().toString()

		taskListService.updateTaskCount(nonExistentUuid, 2)
		assertTrue(taskListService.listTasks().isEmpty())

		verify(schedulerMock, times(0)).schedule(anyString(), any(), anyInt())
	}

	@Test
	fun `Can create, update and finish task`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val countOriginal = 1
		val countUpdated = 2

		taskListService.createTask(uuid, value, countOriginal)
		assertEquals(countOriginal, taskListService.listTasks().fetch(uuid).second)

		taskListService.updateTaskCount(uuid, countUpdated)
		assertEquals(countUpdated, taskListService.listTasks().fetch(uuid).second)

		taskListService.finishTask(uuid)
		assertTrue(taskListService.listTasks().isEmpty())

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(countOriginal))
	}

	@Test
	fun `Finishing non-existent task will not produce exception`() {
		val uuid = UUID.randomUUID().toString()

		taskListService.finishTask(uuid)
		assertTrue(taskListService.listTasks().isEmpty())

		verify(schedulerMock, times(0)).schedule(anyString(), any(), anyInt())
	}


	private fun createRequestData() = // TODO: Duplicate
		SoknadarkivschemaBuilder()
			.withBehandlingsid(UUID.randomUUID().toString())
			.withMottatteDokumenter(MottattDokumentBuilder()
				.withMottatteVarianter(MottattVariantBuilder()
					.withUuid(UUID.randomUUID().toString())
					.build())
				.build())
			.build()

	/**
	 * Infix helper function used to silence warnings that values could be null.
	 */
	private infix fun <K, V> Map<K, V>.fetch(key: K) = this[key] ?: error("Expected value")
}
