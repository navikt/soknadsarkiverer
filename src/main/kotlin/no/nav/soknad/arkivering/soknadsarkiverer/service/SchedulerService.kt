package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ScheduledFuture

private val logger = LoggerFactory.getLogger(object{}::class.java.`package`.name)

@Service
class SchedulerService(val archiverScheduler: ThreadPoolTaskScheduler,
											 val archiverService: ArchiverService,
											 val appConfiguration: AppConfiguration) {

	fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int): ScheduledFuture<*> {

		val task = ArchivingTask(key, soknadarkivschema, archiverService)

		val secondsToWait = getSecondsToWait(attempt)
		val scheduledTime = Instant.now().plusSeconds(secondsToWait)

		logger.info("For '$key': About to schedule attempt $attempt at job in $secondsToWait seconds")
		return archiverScheduler.schedule(task, scheduledTime)
	}

	private fun getSecondsToWait(attempt: Int): Long {
		val index = if (attempt < appConfiguration.config.retryTime.size)
			attempt
		else
			appConfiguration.config.retryTime.lastIndex

		return appConfiguration.config.retryTime[index].toLong()
	}

	class ArchivingTask(private val key: String, private val soknadarkivschema: Soknadarkivschema, private val archiverService: ArchiverService) : Runnable {
		override fun run() {
			try {
				archiverService.archive(key, soknadarkivschema)
			} catch (e: Exception) {
				logger.error("Error when performing scheduled task", e)
			}
		}
	}
}
