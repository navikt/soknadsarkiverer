package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

class SchedulerTests {

	@Test
	fun `shutdown stops both task schedulers`() {
		val scheduler = Scheduler()
		scheduler.setup()

		scheduler.shutdown()

		assertTrue(schedulerField(scheduler, "normalTaskScheduler").scheduledExecutor.isShutdown)
		assertTrue(schedulerField(scheduler, "singleTaskScheduler").scheduledExecutor.isShutdown)
	}

	private fun schedulerField(scheduler: Scheduler, name: String): ThreadPoolTaskScheduler {
		val field = Scheduler::class.java.getDeclaredField(name)
		field.isAccessible = true
		return field.get(scheduler) as ThreadPoolTaskScheduler
	}
}
