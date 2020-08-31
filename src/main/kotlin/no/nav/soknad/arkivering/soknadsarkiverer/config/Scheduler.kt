package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class Scheduler {

	@Autowired
	private lateinit var threadPoolTaskScheduler: ThreadPoolTaskScheduler

	@Bean
	fun threadPoolTaskScheduler() = ThreadPoolTaskScheduler().also {
		it.poolSize = 5
		it.setThreadNamePrefix("ThreadPoolTaskScheduler")
	}

	fun schedule(task: () -> Unit, startTime: Instant) {
		threadPoolTaskScheduler.schedule(task, startTime)
	}
}
