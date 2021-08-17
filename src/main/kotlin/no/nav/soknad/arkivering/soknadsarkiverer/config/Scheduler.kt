package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class Scheduler {

	@Autowired
	private lateinit var normalTaskScheduler: ThreadPoolTaskScheduler

	@Autowired
	private lateinit var singleTaskScheduler: ThreadPoolTaskScheduler

	@Autowired
	private lateinit var selfDestructScheduler: ThreadPoolTaskScheduler

	@Bean(name = ["normalTaskScheduler"])
	fun normalTaskScheduler() = threadPoolTaskScheduler(5)

	@Bean(name = ["singleTaskScheduler"])
	fun singleTaskScheduler() = threadPoolTaskScheduler(1)

	@Bean(name = ["selfDestructScheduler"])
	fun selfDestructScheduler() = threadPoolTaskScheduler(1)

	fun threadPoolTaskScheduler(poolSize: Int) = ThreadPoolTaskScheduler().also {
		it.poolSize = poolSize
		it.setThreadNamePrefix("ThreadPoolTaskSchedulerOfSize${poolSize}_")
	}


	fun schedule(task: () -> Unit, startTime: Instant) {
		normalTaskScheduler.schedule(task, startTime)
	}

	fun scheduleSingleTask(task: () -> Unit, startTime: Instant) {
		singleTaskScheduler.schedule(task, startTime)
	}

	fun scheduleSelfDestruct(task: () -> Unit, startTime: Instant) {
		selfDestructScheduler.schedule(task, startTime)
	}
}
