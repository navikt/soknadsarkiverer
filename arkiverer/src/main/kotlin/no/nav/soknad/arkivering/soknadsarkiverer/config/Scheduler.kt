package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Instant
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

@EnableScheduling
@Configuration
class Scheduler {

	private val normalTaskScheduler = threadPoolTaskScheduler(5)
	private val singleTaskScheduler = threadPoolTaskScheduler(1)

	private fun threadPoolTaskScheduler(poolSize: Int) = ThreadPoolTaskScheduler().also {
		it.poolSize = poolSize
		it.setThreadNamePrefix("ThreadPoolTaskSchedulerOfSize${poolSize}_")
		it.setWaitForTasksToCompleteOnShutdown(true)
		it.setAwaitTerminationSeconds(30)
	}

	@PostConstruct
	fun setup() {
		normalTaskScheduler.initialize()
		singleTaskScheduler.initialize()
	}


	fun schedule(task: () -> Unit, startTime: Instant) {
		normalTaskScheduler.schedule(task, startTime)
	}

	fun scheduleSingleTask(task: () -> Unit, startTime: Instant) {
		singleTaskScheduler.schedule(task, startTime)
	}

	@PreDestroy
	fun shutdown() {
		singleTaskScheduler.shutdown()
		normalTaskScheduler.shutdown()
	}
}
