package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ScheduledFuture

@Service
class SchedulerService(val archiverScheduler: ThreadPoolTaskScheduler,
											 val archiverService: ArchiverService,
											 val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)


	fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int, taskListService: TaskListService): ScheduledFuture<*> {

		val task = ArchivingTask(key, soknadarkivschema, taskListService)

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

	private inner class ArchivingTask(private val key: String,
																		private val soknadarkivschema: Soknadarkivschema,
																		private val taskListService: TaskListService) : Runnable {
		override fun run() {
			try {
				archiverService.archive(key, soknadarkivschema)

			} catch (e: ArchivingException) {
				// Do nothing, these should already have been logged
			} catch (e: Exception) {
				logger.error("Error when performing scheduled task", e)
			}
			taskListService.unlock(key)
		}
	}
}
