package no.nav.soknad.arkivering.soknadsarkiverer.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.config.isBusy
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.HealthCheck
import no.nav.soknad.arkivering.soknadsarkiverer.utils.InnsendingTopicMsgBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.ContextClosedEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class TaskListServiceLifecycleTests {

	@ParameterizedTest
	@CsvSource("STARTED,false", "STARTED,true", "ARCHIVED,false", "ARCHIVED,true", "FAILURE,false", "FAILURE,true")
	fun `context shutdown drains coroutine work before closing the publisher`(state: EventTypes, bootstrapping: Boolean) {
		Fixture(state).use { fixture ->
			Executors.newSingleThreadExecutor().use { executor ->
				fixture.startTask(bootstrapping)
				val shutdownStarted = CountDownLatch(1)
				fixture.context.addApplicationListener {
					if (it is ContextClosedEvent) shutdownStarted.countDown()
				}
				val shutdown = executor.submit { fixture.context.close() }
				try {
					assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS))
					assertFalse(
						fixture.publisherClosed.await(200, TimeUnit.MILLISECONDS),
						"The publisher must stay open until the coroutine and its final publication finish"
					)
				} finally {
					fixture.releaseTask.countDown()
					shutdown.get(5, TimeUnit.SECONDS)
				}
			}
			assertEquals(fixture.expectedEvents + "publisher closed", fixture.events)
		}
	}

	@ParameterizedTest
	@EnumSource(value = EventTypes::class, names = ["STARTED", "ARCHIVED", "FAILURE"])
	fun `preStop waits for feedback and final state publication`(state: EventTypes) {
		Fixture(state).use { fixture ->
			Executors.newSingleThreadExecutor().use { executor ->
				fixture.startTask()
				try {
					assertTrue(isBusy(fixture.applicationState), "In-flight coroutine work must be counted by preStop")
					val stopReturned = CountDownLatch(1)
					val stop = executor.submit {
						HealthCheck(fixture.applicationState, fixture.metrics, "").stop()
						stopReturned.countDown()
					}
					assertFalse(stopReturned.await(200, TimeUnit.MILLISECONDS))
					fixture.releaseTask.countDown()
					stop.get(5, TimeUnit.SECONDS)
					assertFalse(isBusy(fixture.applicationState))
					assertEquals(fixture.expectedEvents, fixture.events)
				} finally {
					fixture.releaseTask.countDown()
				}
			}
		}
	}

	private class Fixture(private val state: EventTypes) : AutoCloseable {
		val applicationState = ApplicationState(alive = true, ready = true)
		val metrics = mockk<ArchivingMetrics>(relaxed = true)
		val events = CopyOnWriteArrayList<String>()
		val releaseTask = CountDownLatch(1)
		val publisherClosed = CountDownLatch(1)
		val context = AnnotationConfigApplicationContext()
		private val taskStarted = CountDownLatch(1)
		private val taskFinished = CountDownLatch(1)
		private val publisher = mockk<KafkaPublisher>(relaxed = true)
		private val archiver = mockk<ArchiverService>(relaxed = true)
		private val safService = mockk<SafServiceInterface>()
		private val submission = InnsendingTopicMsgBuilder().build()
		private val taskListService: TaskListService
		val expectedEvents = when (state) {
			EventTypes.STARTED -> listOf("ARCHIVED")
			EventTypes.ARCHIVED -> listOf("feedback", "FINISHED")
			else -> listOf("feedback")
		}

		init {
			every { publisher.close() } answers {
				events.add("publisher closed")
				publisherClosed.countDown()
			}
			every { publisher.putProcessingEventOnTopic(any(), any(), any()) } answers {
				events.add(secondArg<ProcessingEvent>().type.name)
				taskFinished.countDown()
			}
			coEvery { archiver.fetchFiles(any(), any()) } returns emptyList()
			every { safService.hentJournalpostGittInnsendingId(any()) } returns null
			every { metrics.endTimer(any()) } answers { awaitRelease() }
			every { archiver.createArkiveringstilbakemelding(any(), any()) } answers {
				awaitRelease()
				events.add("feedback")
				if (state == EventTypes.FAILURE) taskFinished.countDown()
			}

			context.registerBean("kafkaPublisher", KafkaPublisher::class.java, Supplier { publisher })
			context.registerBean(Scheduler::class.java, Supplier { Scheduler() })
			context.registerBean(
				"taskListService",
				TaskListService::class.java,
				Supplier {
					TaskListService(
						archiver, safService, 0, listOf(0, 0),
						applicationState, context.getBean(Scheduler::class.java), metrics, publisher
					)
				},
				{ it.setDependsOn("scheduler", "kafkaPublisher") }
			)
			context.refresh()
			taskListService = context.getBean(TaskListService::class.java)
		}

		fun startTask(bootstrapping: Boolean = false) {
			taskListService.addOrUpdateTask(submission.innsendingsId, submission, state, bootstrapping)
			assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
		}

		private fun awaitRelease() {
			taskStarted.countDown()
			check(releaseTask.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release coroutine work" }
		}

		override fun close() {
			releaseTask.countDown()
			if (taskStarted.count == 0L) {
				check(taskFinished.await(5, TimeUnit.SECONDS)) { "Coroutine work did not finish" }
			}
			context.close()
		}
	}
}
