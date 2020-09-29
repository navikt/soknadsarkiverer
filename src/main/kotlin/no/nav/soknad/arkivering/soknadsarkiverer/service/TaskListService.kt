package no.nav.soknad.arkivering.soknadsarkiverer.service

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Scope(SCOPE_SINGLETON) // This is a stateful component so it must be singleton
@Service
class TaskListService(private val archiverService: ArchiverService,
											private val appConfiguration: AppConfiguration,
											private val scheduler: Scheduler) {
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
			TimeUnit.SECONDS.sleep(1) // When recreating state, there could be more updates coming. Wait a little while to make sure we don't start prematurely.
			task.isRunningLock.release()
			start(key)
		}
	}

	private fun start(key: String) {
		val task = tasks[key]
		if (task != null && task.isRunningLock.tryAcquire())
			schedule(key, task.value, task.count)
	}

	fun pauseAndStart(key: String) {
		val task = tasks[key]
		if (task != null) {

			logger.info("$key: Waiting to acquire lock")
			task.isRunningLock.acquire()
			logger.info("$key: Acquired lock, will start rerun")
			schedule(key, task.value, 0)

		} else {
			logger.info("$key: Failed to find task, maybe it it already finished?")
		}
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
		}
	}

	fun finishTask(key: String) {
		if (tasks.containsKey(key)) {
			logger.info("$key: Finishing task")
			tasks.remove(key)
		} else {
			logger.info("$key: Tried to finish task, but it is already finished")
		}
	}

	internal fun listTasks(key: String? = null) = tasks
		.filter { if (key != null) it.key == key else true }
		.mapValues { it.value.count to it.value.isRunningLock }

	fun getSoknadarkivschema(key: String) = tasks[key]?.value

	private fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int) {

		if (attempt > appConfiguration.config.retryTime.size) {
			logger.warn("$key: Too many attempts ($attempt), will not try again")
			tasks[key]?.isRunningLock?.release()
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
			logger.info("$key: Will now start to archive")
			archiverService.archive(key, soknadarkivschema)
			return

		} catch (e: ArchivingException) {
			// Log nothing, the Exceptions of this type are supposed to already have been logged
			retry(key)

		} catch (e: Exception) {
			logger.error("$key: Error when performing scheduled task", e)
			retry(key)

		} catch (t: Throwable) {
			logger.error("$key: Serious error when performing scheduled task", t)
			retry(key)
			throw t
		}
	}

	private fun retry(key: String) {
		incrementCountAndSetToNotRunning(key)
		start(key)
	}


	private class Task(val value: Soknadarkivschema,
										 val count: Int,
										 val timeStarted: LocalDateTime,
										 val isRunningLock: Semaphore)
}
