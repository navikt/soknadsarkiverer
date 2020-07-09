package no.nav.soknad.arkivering.soknadsarkiverer.service

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Service
class TaskListService(private val archiverService: ArchiverService,
											private val appConfiguration: AppConfiguration,
											private val scheduler: Scheduler)  {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val tasks = hashMapOf<String, Task>()

	@Synchronized
	fun addOrUpdateTask(key: String, soknadarkivschema: Soknadarkivschema, count: Int) {
		if (!tasks.containsKey(key)) {
			tasks[key] = Task(soknadarkivschema, count, LocalDateTime.now(), Semaphore(1).also { it.acquire() })
			logger.info("$key: Created task")
			GlobalScope.launch { startNewlyCreatedTask(key) }
		} else {
			updateCount(key, count)
		}
	}

	private fun startNewlyCreatedTask(key: String) {
		val task = tasks[key]
		if (task != null) {
			logger.info("$key: starting newly created task")
			TimeUnit.SECONDS.sleep(1) // When recreating state, there could be more updates coming. Wait a little while to make sure we don't start prematurely.
			task.isRunningLock.release()
			logger.info("$key: Released lock")
			start(key)
		}
	}

	private fun start(key: String) {
		val task = tasks[key]
		if (task != null && task.isRunningLock.tryAcquire()) {
			logger.info("$key: Acquired lock, will now call schedule")
			schedule(key, task.value, task.count)
		} else
			logger.info("$key: task == null: ${task == null}, or failed to acquire lock")
	}

	private fun incrementCountAndSetToNotRunning(key: String) {
		val task = tasks[key]
		if (task != null) {
			updateCount(key, task.count + 1)
			task.isRunningLock.release()
		}
	}

	@Synchronized
	private fun updateCount(key: String, newCount: Int) {
		val task = tasks[key]
		if (task != null && task.count < newCount) {
			tasks[key] = Task(task.value, newCount, task.timeStarted, task.isRunningLock)
			logger.info("$key: Updated task with count $newCount")
		}
	}

	fun finishTask(key: String) {
		if (tasks.containsKey(key)) {
			logger.info("$key: About to remove task")
			tasks.remove(key)
			logger.info("$key: Task removed")
		} else {
			logger.error("$key: Task is already finished")
		}
	}

	internal fun listTasks() = tasks // TODO: Return only data needed, not the whole class

	private fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int) {

		if (attempt > appConfiguration.config.retryTime.size) {
			logger.warn("$key: Too many attempts ($attempt), will not try again")
			return
		}

		val task = { tryToArchive(key, soknadarkivschema) }

		val secondsToWait = getSecondsToWait(attempt)
		val scheduledTime = Instant.now().plusSeconds(secondsToWait)

		logger.info("$key: About to schedule attempt $attempt at job in $secondsToWait seconds")
		scheduler.schedule(task, scheduledTime)
	}

	private fun getSecondsToWait(attempt: Int): Long {
		val index = if (attempt < appConfiguration.config.retryTime.size)
			attempt
		else
			appConfiguration.config.retryTime.lastIndex

		return appConfiguration.config.retryTime[index].toLong()
	}

	internal fun tryToArchive(key: String, soknadarkivschema: Soknadarkivschema) {
		try {
			logger.info("$key: About to archive")
			archiverService.archive(key, soknadarkivschema)
			logger.info("$key: Finished archiving")
			finishTask(key)
			return

		} catch (e: ArchivingException) {
			// Do nothing, the Exceptions of this type are supposed to already have been logged
		} catch (e: Exception) {
			logger.error("$key: Error when performing scheduled task", e)
		}
		incrementCountAndSetToNotRunning(key)
		start(key)
	}
}

internal class Task(val value: Soknadarkivschema, val count: Int, val timeStarted: LocalDateTime, val isRunningLock: Semaphore) // TODO: Make class private
