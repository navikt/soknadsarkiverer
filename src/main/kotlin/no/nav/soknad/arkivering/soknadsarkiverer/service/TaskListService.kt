package no.nav.soknad.arkivering.soknadsarkiverer.service

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.*
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Semaphore

@Scope(SCOPE_SINGLETON) // This is a stateful component so it must be singleton
@Service
class TaskListService(
	private val archiverService: ArchiverService,
	private val appConfiguration: AppConfiguration,
	private val scheduler: Scheduler,
	private val metrics: ArchivingMetrics,
	private val kafkaPublisher: KafkaPublisher
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val tasks = hashMapOf<String, Task>()
	private val loggedTaskStates = hashMapOf<String, EventTypes>()
	private val currentTaskStates = hashMapOf<String, EventTypes>()
	private val jobMap = hashMapOf<String, Job>()

	private val processRun: Boolean = false // Hvis true så vil all behandling av ulike states på søknader initieres fra topology. Pt vil testene feilene hvis = true

	private val startUpEndTime = Instant.now().plusSeconds(appConfiguration.config.secondsAfterStartupBeforeStarting)

	init {
		logger.info("startUpEndTime=$startUpEndTime")
	}

	@Synchronized
	fun addOrUpdateTask(
		key: String,
		soknadarkivschema: Soknadarkivschema,
		state: EventTypes,
		isBootstrappingTask: Boolean = false
	) {

		if (!tasks.containsKey(key)) {
			newTask(key, soknadarkivschema, state, isBootstrappingTask)
		} else {
			updateTaskState(key, state)
		}
	}

	private fun newTask(
		key: String,
		soknadarkivschema: Soknadarkivschema,
		state: EventTypes,
		isBootstrappingTask: Boolean
	) {

		loggedTaskStates[key] = state
		currentTaskStates[key] = state
		val isRunningLock = Semaphore(1).also { it.acquire() }
		tasks[key] = Task(soknadarkivschema, 0, LocalDateTime.now(), isRunningLock, isBootstrappingTask)

		metrics.addTask()
		logger.info("$key: Created new task with state = $state")

		startNewlyCreatedTask(key, state)
	}

	private fun startNewlyCreatedTask(key: String, state: EventTypes) {
		if (tasks[key] != null) {
			jobMap[key] = GlobalScope.launch { start(key, state) } // Start new thread for handling archiving of application with key
		}
	}

	private fun updateTaskState(key: String, state: EventTypes) {
		if (tasks[key] != null) {
			val task = tasks[key]!!
			loggedTaskStates[key] = state
			logger.debug("$key: Updated task, new state - $state")
			if (processRun && task.isRunningLock.tryAcquire()) {
				jobMap[key] = GlobalScope.launch { start(key, state) } // Start new thread for handling archiving of application with key
			}
		} else {
			logger.debug("$key: Ignoring state - $state")
		}
	}

	private fun setStateChange(key: String, state: EventTypes, soknadarkivschema: Soknadarkivschema, attempt: Int) {
		if (processRun) {
			val jobThread = jobMap.remove(key)
			jobThread?.cancel()
			currentTaskStates[key] = state
			tasks[key]?.isRunningLock?.release()
			createProcessingEvent(key, state)
		} else {
			createProcessingEvent(key, state)
			currentTaskStates[key] = state
			schedule(key, soknadarkivschema, attempt)
		}
	}

	private suspend fun start(key: String, state: EventTypes) = withContext(Dispatchers.IO) {
		if (tasks[key] != null && startUpEndTime.isAfter(Instant.now()) && (state == EventTypes.RECEIVED || state == EventTypes.STARTED || state == EventTypes.ARCHIVED)) {
			// When recreating state, there could be more state updates in the processLoggTopic.
			// Wait a little while to make sure we don't start before all queued states are read inorder to process the most recent state.
			logger.debug("$key: Sleeping for ${appConfiguration.config.secondsAfterStartupBeforeStarting} sec state - $state")
			delay(appConfiguration.config.secondsAfterStartupBeforeStarting * 1000)
			logger.debug("$key: Slept ${appConfiguration.config.secondsAfterStartupBeforeStarting} sec state - $state")
		}

		val task = tasks[key]
		if (task != null) {
			// Process application given most recent logged processing state
			currentTaskStates[key] = loggedTaskStates[key]!!
			schedule(key, task.value, task.count)
		} else {
			logger.debug("$key: cannot start empty task, state - $state")
		}
	}

	// Starte på nytt task som har failed. Må resette task.count og sette state til STARTED.
	// Setter processingEvent for å trigge re-start fra POD som kjører partition.
	fun startPaNytt(key: String) {
		logger.info("$key: state = FAILURE. Ready for next state STARTED")
		val task = tasks[key]
		if (task != null && loggedTaskStates[key] == EventTypes.FAILURE) {
			tasks.remove(key)
		}
		createProcessingEvent(key, EventTypes.STARTED)
	}


	private fun incrementRetryCount(key: String): Int {
		val task = tasks[key]
		if (task != null) {
			updateCount(key, task.count + 1)
			return task.count + 1
		}
		return 0
	}

	@Synchronized
	private fun updateCount(key: String, newCount: Int) {
		val task = tasks[key]
		if (task != null && task.count < newCount) {
			tasks[key] = Task(task.value, newCount, task.timeStarted, task.isRunningLock)
			metrics.setTasksGivenUpOn(tasks.values.filter { it.count > appConfiguration.config.retryTime.size }.count().toDouble())
		}
	}

	internal fun listTasks(key: String? = null) = tasks
		.filter { if (key != null) it.key == key else true }
		.mapValues { it.value.count to it.value.isRunningLock }

	fun getSoknadarkivschema(key: String) = tasks[key]?.value

	fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int? = 0) {

		if (tasks[key] == null || loggedTaskStates[key] == EventTypes.FAILURE || loggedTaskStates[key] == EventTypes.FINISHED) {
			logger.warn("$key: Too many attempts ($attempt) or loggedstate ${loggedTaskStates[key]}, will not try again")

		} else {
			logger.debug("$key: In schedule. Attempts: ($attempt), loggedstate: ${loggedTaskStates[key]}, currentTaskState: ${currentTaskStates[key]}")

			when (currentTaskStates[key]) {
				EventTypes.RECEIVED -> receivedState(key, soknadarkivschema, attempt)
				EventTypes.STARTED  -> archiveState(key, soknadarkivschema, attempt)
				EventTypes.ARCHIVED -> deleteFilesState(key, soknadarkivschema, attempt)
				EventTypes.FAILURE  -> failTask(key)
				EventTypes.FINISHED -> finishTask(key)
				else -> logger.error("$key: - Unexpected state ${loggedTaskStates[key]}")
			}
		}
	}

	fun receivedState(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int? = 0) {
		logger.info("$key: state = RECEIVED. Ready for next state STARTED")
		setStateChange(key, EventTypes.STARTED, soknadarkivschema, attempt!!)
	}

	fun archiveState(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int? = 0) {
		val secondsToWait = getSecondsToWait(attempt!!)
		val scheduledTime = Instant.now().plusSeconds(secondsToWait)
		val task = { tryToArchive(key, soknadarkivschema) }
		logger.info("$key: state = STARTED. About to schedule attempt $attempt at job in $secondsToWait seconds")

		if (tasks[key]?.isBootstrappingTask == true)
			scheduler.scheduleSingleTask(task, scheduledTime)
		else
			scheduler.schedule(task, scheduledTime)
	}

	fun deleteFilesState(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int? = 0) {
		logger.info("$key: state = ARCHIVED. About deleteFiles in attempt $attempt")
		tryToDeleteFiles(key, soknadarkivschema)
	}

	// Remove task and cancel thread
	fun finishTask(key: String) {
		if (tasks.containsKey(key)) {

			tasks.remove(key)
			loggedTaskStates.remove(key)
			metrics.removeTask()
			val jobThread = jobMap.remove(key)
			logger.info("$key: Finished task")
			jobThread?.cancel()

		} else {
			logger.info("$key: Tried to finish task, but it is already finished")
		}
	}

	//  Keep task. Note that the thread is canceled and new coroutine must be started in order to resume processing
	fun failTask(key: String) {
		if (tasks.containsKey(key)) {

			loggedTaskStates[key] = EventTypes.FAILURE
			metrics.setTasksGivenUpOn(1.0)
			val jobThread = jobMap.remove(key)
			logger.info("$key: Failed task")
			tasks[key]?.isRunningLock?.release()
			jobThread?.cancel()

		} else {
			logger.info("$key: Tried to fail task, but it is already finished")
		}
	}

	private fun getSecondsToWait(attempt: Int): Long {
		val index = if (attempt < appConfiguration.config.retryTime.size)
			attempt
		else
			appConfiguration.config.retryTime.lastIndex

		return appConfiguration.config.retryTime[index].toLong()
	}

	internal fun tryToArchive(key: String, soknadarkivschema: Soknadarkivschema) {
		var nextState: EventTypes? = null
		val timer = metrics.archivingLatencyStart()
		val histogram = metrics.archivingLatencyHistogramStart(soknadarkivschema.arkivtema)
		try {
			logger.info("$key: Will now start to archive")
			val files = archiverService.fetchFiles(key, soknadarkivschema)

			protectFromShutdownInterruption(appConfiguration) {
				archiverService.archive(key, soknadarkivschema, files)
				nextState = EventTypes.ARCHIVED
			}

			logger.info("$key: Finished archiving")

		} catch (e: ApplicationAlreadyArchivedException) {
			// Log nothing, the Exceptions of this type are supposed to already have been logged
			nextState = EventTypes.ARCHIVED

		} catch (e: ArchivingException) {
			// Log nothing, the Exceptions of this type are supposed to already have been logged
			nextState = retry(key)

		} catch (e: Exception) {
			nextState = when (e.cause) {
				is FilesAlreadyDeletedException -> {
					// File(s) already deleted in filestorage indicating that the application is already archived.
					logger.warn("$key: Files gone from filestorage continues without archiving", e)
					EventTypes.ARCHIVED
				}
				is ApplicationAlreadyArchivedException -> {
					// File(s) already deleted in filestorage indicating that the application is already archived.
					logger.warn("$key: Application already archived continues without archiving", e)
					EventTypes.ARCHIVED
				}
				is ShuttingDownException -> {
					logger.warn("$key: Will not start to archive - application is shutting down.")
					null
				}
				else -> {
					logger.error("$key: Error when performing scheduled task", e)
					retry(key)
				}
			}

		} catch (t: Throwable) {
			logger.error("$key: Serious error when performing scheduled task", t)
			nextState = retry(key)
			throw t

		} finally {
			metrics.endTimer(timer)
			metrics.endHistogramTimer(histogram)
			metrics.numberOfAttachmentHistogramSet(
				soknadarkivschema.mottatteDokumenter.size.toDouble(),
				soknadarkivschema.arkivtema
			)
			if (nextState != null && tasks[key] != null) {
				setStateChange(key, nextState!!, soknadarkivschema, tasks[key]?.count!!)
			}
		}
	}

	internal fun tryToDeleteFiles(key: String, soknadarkivschema: Soknadarkivschema) {
		try {
			logger.info("$key: Will now start to delete files")
			archiverService.deleteFiles(key, soknadarkivschema)
			logger.info("$key: Finished deleting files")

		} catch (e: ArchivingException) {
			// Log nothing, the Exceptions of this type are supposed to already have been logged

		} catch (e: Exception) {
			logger.error("$key: Error when performing scheduled task to delete files", e)

		} catch (t: Throwable) {
			logger.error("$key: Serious error when performing scheduled task to delete files", t)
			throw t

		} finally {
			if (tasks[key] != null) {
				setStateChange(key, EventTypes.FINISHED, soknadarkivschema, tasks[key]?.count!!)
			}
		}
	}

	private fun retry(key: String): EventTypes? {
		if (tasks[key] == null)
			return null

		val count = incrementRetryCount(key)
		return if (count >= appConfiguration.config.retryTime.size) {
			EventTypes.FAILURE
		} else {
			EventTypes.STARTED
		}
	}


	private fun createProcessingEvent(key: String, type: EventTypes) {
		kafkaPublisher.putProcessingEventOnTopic(key, ProcessingEvent(type))
	}


	private class Task(
		val value: Soknadarkivschema,
		val count: Int,
		val timeStarted: LocalDateTime,
		val isRunningLock: Semaphore,
		val isBootstrappingTask: Boolean = false
	)
}
