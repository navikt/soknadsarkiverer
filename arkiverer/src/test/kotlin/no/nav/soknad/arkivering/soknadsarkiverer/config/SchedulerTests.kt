package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class SchedulerTests {

	@Test
	fun `shutdown stops both task schedulers`() {
		val scheduler = Scheduler()
		scheduler.setup()
		val delayedTask = CountDownLatch(1)
		scheduler.schedule({ delayedTask.countDown() }, Instant.now().plusSeconds(3600))
		scheduler.scheduleSingleTask({ delayedTask.countDown() }, Instant.now().plusSeconds(3600))

		scheduler.shutdown()

		assertTrue(schedulerField(scheduler, "normalTaskScheduler").scheduledExecutor.isShutdown)
		assertTrue(schedulerField(scheduler, "singleTaskScheduler").scheduledExecutor.isShutdown)
		assertTrue(schedulerField(scheduler, "normalTaskScheduler").scheduledExecutor.isTerminated)
		assertTrue(schedulerField(scheduler, "singleTaskScheduler").scheduledExecutor.isTerminated)
		assertEquals(1L, delayedTask.count)
	}

	@Test
	fun `running tasks finish before the Kafka publisher is closed`() {
		val events = CopyOnWriteArrayList<String>()
		val taskStarted = CountDownLatch(1)

		AnnotationConfigApplicationContext().use { context ->
			// Register the scheduler first so default reverse-registration order would close the publisher first.
			context.registerBean(Scheduler::class.java, Supplier { Scheduler() })
			context.registerBean("kafkaPublisher", AutoCloseable::class.java, Supplier { AutoCloseable { events.add("publisher closed") } })
			context.refresh()

			context.getBean(Scheduler::class.java).schedule({
				taskStarted.countDown()
				Thread.sleep(300)
				events.add("task finished")
			}, Instant.now())
			assertTrue(taskStarted.await(5, TimeUnit.SECONDS))
		}

		assertEquals(listOf("task finished", "publisher closed"), events)
	}

	private fun schedulerField(scheduler: Scheduler, name: String): ThreadPoolTaskScheduler {
		val field = Scheduler::class.java.getDeclaredField(name)
		field.isAccessible = true
		return field.get(scheduler) as ThreadPoolTaskScheduler
	}
}
