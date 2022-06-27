package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import io.prometheus.client.CollectorRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppStateConfig
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class])
@ActiveProfiles("test")
@Import(*[TaskListConfig::class,AppStateConfig::class,MetricsTestConfig::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TaskListServiceTests {



  @MockBean
	private lateinit var archiverService : ArchiverService  //= mock<ArchiverService>()
	@MockBean
	private lateinit var scheduler : Scheduler // = mock<Scheduler>()
	@MockBean
	private lateinit var kafkaPublisher : KafkaPublisher // = mock<KafkaPublisher>()
  @Autowired
  private lateinit var metrics : ArchivingMetrics//= ArchivingMetrics(CollectorRegistry.defaultRegistry)


	@Autowired
	private lateinit var  taskListService : TaskListService
	private  val soknadarkivschema = createSoknadarkivschema()



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

		verify(archiverService, timeout(10_000).times(1)).archive(eq(key), any(), any())
		verify(archiverService, timeout(10_000).times(1)).deleteFiles(eq(key), any())
		verify(scheduler, timeout(10_000).times(1)).schedule(any(), any())
		verify(kafkaPublisher, timeout(10_000).times(1)).putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.FINISHED)), any())
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

@TestConfiguration
class MetricsTestConfig {

	@Bean
	fun metricsCinfig() =  ArchivingMetrics(CollectorRegistry.defaultRegistry)

}
