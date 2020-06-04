package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock

@Configuration
class ThreadPoolTaskSchedulerConfig {

	@Bean(name = ["archiverScheduler"])
	fun threadPoolTaskScheduler() = ThreadPoolTaskScheduler().also {
		it.poolSize = 5
		it.setThreadNamePrefix("ThreadPoolTaskScheduler")
	}

	@Bean
	fun clock(): Clock = Clock.systemUTC() //TODO: Is a clock needed?
}
