package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Clock

private val log = LoggerFactory.getLogger(object{}::class.java.`package`.name)

@Service
class SchedulerService(private val schedulingDependencies: SchedulingDependencies) {

	fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int = 0) {
		schedule(key, soknadarkivschema, attempt, schedulingDependencies)
	}

	class ArchivingTask(private val key: String, private val soknadarkivschema: Soknadarkivschema, private val attempt: Int,
											private val schedulingDependencies: SchedulingDependencies) : Runnable {
		override fun run() {
			try {
				schedulingDependencies.archiverService.archive(key, soknadarkivschema)
			} catch (e: Exception) {

				val maxNumberOfAttempts = schedulingDependencies.appConfiguration.config.retryTime.size
				if (attempt < maxNumberOfAttempts) {
					schedule(key, soknadarkivschema, attempt + 1, schedulingDependencies)
				} else {
					log.warn("Have exceeded $maxNumberOfAttempts attempts for key '$key'. Will not attempt again.")
				}
			}
		}
	}
}

private fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int, schedulingDependencies: SchedulingDependencies) {

	val task = SchedulerService.ArchivingTask(key, soknadarkivschema, attempt, schedulingDependencies)

	val secondsToWait = if (attempt == 0) 0 else schedulingDependencies.appConfiguration.config.retryTime[attempt - 1].toLong()
	val scheduledTime = schedulingDependencies.clock.instant().plusSeconds(secondsToWait)

	log.info("For '$key': About to schedule attempt $attempt at job in $secondsToWait seconds")
	schedulingDependencies.archiverScheduler.schedule(task, scheduledTime)
}

@Service
class SchedulingDependencies(val archiverScheduler: ThreadPoolTaskScheduler,
														 val clock: Clock, // TODO: Is a clock needed?
														 val archiverService: ArchiverService,
														 val appConfiguration: AppConfiguration)
