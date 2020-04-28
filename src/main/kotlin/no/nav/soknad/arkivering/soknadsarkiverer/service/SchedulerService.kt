package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class SchedulerService(private val schedulingDependencies: SchedulingDependencies) {

	fun schedule(key: String, soknadarkivschema: Soknadarkivschema) {
		schedule(key, soknadarkivschema, 0, schedulingDependencies)
	}

	class ArchivingTask(private val key: String, private val soknadarkivschema: Soknadarkivschema, private val attempt: Int,
											private val schedulingDependencies: SchedulingDependencies) : Runnable {
		override fun run() {
			try {
				schedulingDependencies.archiverService.archive(key, soknadarkivschema)
			} catch (e: Exception) {

				if (attempt < schedulingDependencies.applicationProperties.kafkaRetrySleepTime.size) {
					// TODO: Log
					schedule(key, soknadarkivschema, attempt + 1, schedulingDependencies)
				} else {
					// TODO: Log
				}
			}
		}
	}
}

private fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int, schedulingDependencies: SchedulingDependencies) {

	val task = SchedulerService.ArchivingTask(key, soknadarkivschema, attempt, schedulingDependencies)

	val secondsToWait = if (attempt == 0) 0 else schedulingDependencies.applicationProperties.kafkaRetrySleepTime[attempt - 1].toLong()
	val scheduledTime = schedulingDependencies.clock.instant().plusSeconds(secondsToWait)

	schedulingDependencies.archiverScheduler.schedule(task, scheduledTime)
}

@Service
class SchedulingDependencies(val archiverScheduler: ThreadPoolTaskScheduler,
														 val clock: Clock,
														 val archiverService: ArchiverService,
														 val applicationProperties: ApplicationProperties)
