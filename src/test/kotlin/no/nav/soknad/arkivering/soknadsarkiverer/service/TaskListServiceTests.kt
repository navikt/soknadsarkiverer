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

	private val schedulerMock = mock<SchedulerService>().also {
		whenever(it.schedule(anyString(), any(), anyInt())).thenReturn(mock())
	}
	private val taskListService = TaskListService(schedulerMock)

	@Test
	fun `Can list Tasks when there are none`() {
		assertTrue(taskListService.listTasks().isEmpty())
	}

	@Test
	fun `Can create task - will schedule`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val count = 0

		taskListService.addOrUpdateTask(uuid, value, count)

		val tasks = taskListService.listTasks()
		assertEquals(1, tasks.size)
		assertEquals(value, tasks.fetch(uuid).first)
		assertEquals(count, tasks.fetch(uuid).second)

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(count))
	}

	@Test
	fun `Can update Task`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val countOriginal = 1
		val countUpdated = 2

		taskListService.addOrUpdateTask(uuid, value, countOriginal)
		assertEquals(countOriginal, taskListService.listTasks().fetch(uuid).second)

		taskListService.addOrUpdateTask(uuid, value, countUpdated)
		assertEquals(countUpdated, taskListService.listTasks().fetch(uuid).second)

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(countOriginal))
		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(countUpdated))
	}

	@Test
	fun `Can create, update and finish task`() {
		val uuid = UUID.randomUUID().toString()
		val value = createRequestData()
		val countOriginal = 1
		val countUpdated = 2

		taskListService.addOrUpdateTask(uuid, value, countOriginal)
		assertEquals(countOriginal, taskListService.listTasks().fetch(uuid).second)

		taskListService.addOrUpdateTask(uuid, value, countUpdated)
		assertEquals(countUpdated, taskListService.listTasks().fetch(uuid).second)

		taskListService.finishTask(uuid)
		assertTrue(taskListService.listTasks().isEmpty())

		verify(schedulerMock, times(1)).schedule(eq(uuid), eq(value), eq(countOriginal))
	}

	@Test
	fun `Finishing non-existent task will not produce exception`() {
		val nonExistentUuid = UUID.randomUUID().toString()

		taskListService.finishTask(nonExistentUuid)
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
