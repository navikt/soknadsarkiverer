package no.nav.soknad.arkivering.soknadsarkiverer.service

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.Constants.MDC_INNSENDINGS_ID
import no.nav.soknad.arkivering.soknadsarkiverer.config.*
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Semaphore

open class TaskListService(
	private val archiverService: ArchiverService,
	private val safService: SafServiceInterface,
	private val startUpSeconds: Long,
	private val secondsBetweenRetries: List<Long>,
	private val applicationState: ApplicationState,
	private val scheduler: Scheduler,
	private val metrics: ArchivingMetrics,
	private val kafkaPublisher: KafkaPublisher
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val tasks = hashMapOf<String, Task>()
	private val loggedTaskStates = hashMapOf<String, EventTypes>()
	private val currentTaskStates = hashMapOf<String, EventTypes>()

	private val processRun: Boolean = true // Hvis true så vil all behandling av ulike states på søknader initieres fra topology. Pt vil noen tester feile hvis  = false

	private val startUpEndTime = Instant.now().plusSeconds(startUpSeconds)

	init {
		logger.info("startUpEndTime=$startUpEndTime")
	}

	@Synchronized
	open fun addOrUpdateTask(
		key: String,
		soknadarkivschema: Soknadarkivschema,
		state: EventTypes,
		isBootstrappingTask: Boolean = false
	) {
		MDC.put(MDC_INNSENDINGS_ID, key)

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

		schedule(key, soknadarkivschema, 0)
	}


	private fun updateTaskState(key: String, state: EventTypes) {
		if (tasks[key] != null) {
			val task = tasks[key]!!
			loggedTaskStates[key] = state
			logger.debug("$key: Updated task, new state - $state")

			updateNoOfFailedMetrics()
			schedule(key, task.value, task.count)
		} else {
			logger.debug("$key: Ignoring state - $state")
		}
	}

	private fun setStateChange(key: String, state: EventTypes, soknadarkivschema: Soknadarkivschema, attempt: Int) {
		MDC.put(MDC_INNSENDINGS_ID, key)
		if (processRun) {
			currentTaskStates[key] = state
			tasks[key]?.isRunningLock?.release()
			createProcessingEvent(key, state)
		} else {
			createProcessingEvent(key, state)
			currentTaskStates[key] = state
			schedule(key, soknadarkivschema, attempt)
		}
	}


	// Starte på nytt task som har failed. Må resette task.count og sette state til STARTED.
	// Setter processingEvent for å trigge re-start fra POD som kjører partition.
	fun startPaNytt(key: String) {
		MDC.put(MDC_INNSENDINGS_ID, key)

		logger.info("$key: state = FAILURE. Ready for next state STARTED")
		val task = tasks[key]
		if (task != null && (loggedTaskStates[key] == EventTypes.FAILURE || loggedTaskStates[key] == EventTypes.FINISHED || loggedTaskStates[key] == EventTypes.ARCHIVED)) {
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
		}
	}

	internal fun listTasks(key: String? = null) = tasks
		.filter { if (key != null) it.key == key else true }
		.mapValues { it.value.count to it.value.isRunningLock }

	internal fun getNumberOfAttempts(key: String): Int? = tasks[key]?.count

	private fun schedule(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int = 0) {

		logger.debug("$key: In schedule. Attempts: ($attempt), loggedstate: ${loggedTaskStates[key]}, currentTaskState: ${currentTaskStates[key]}")

		when (currentTaskStates[key]) {
			EventTypes.RECEIVED -> receivedState(key, soknadarkivschema, attempt)
			EventTypes.STARTED  -> archiveState(key, soknadarkivschema, attempt)
			EventTypes.ARCHIVED -> deleteFilesState(key, soknadarkivschema, attempt)
			EventTypes.FAILURE  -> failTask(key)
			EventTypes.FINISHED -> finishTask(key)
			else -> {
				logger.error("$key: - Unexpected state ${currentTaskStates[key]} - Will assume it was ${EventTypes.RECEIVED}")
				receivedState(key, soknadarkivschema, attempt)
			}
		}
	}

	fun receivedState(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int = 0) {
		if (tasks[key] != null && startUpEndTime.isAfter(Instant.now()) ) {
			// When recreating state, there could be more state updates in the processLoggTopic.
			// Wait a little while to make sure we don't start before all queued states are read inorder to process the most recent state.
			val task = { receivedState(key, soknadarkivschema, attempt) }
			scheduler.scheduleSingleTask(task, Instant.now().plusSeconds(startUpSeconds))
		} else {
			logger.info("$key: state = RECEIVED. Ready for next state STARTED")
			setStateChange(key, EventTypes.STARTED, soknadarkivschema, attempt)
		}
	}

	private fun archiveState(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int = 0) {
		val secondsToWait = getSecondsToWait(attempt)
		val scheduledTime = Instant.now().plusSeconds(secondsToWait)
		val task = { tryToArchive(key, soknadarkivschema, attempt) }
		logger.info("$key: state = STARTED. About to schedule attempt $attempt at job in $secondsToWait seconds")

		if (tasks[key]?.isBootstrappingTask == true)
			scheduler.scheduleSingleTask(task, scheduledTime)
		else
			scheduler.schedule(task, scheduledTime)
	}

	private fun deleteFilesState(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int = 0) {
		logger.info("$key: state = ARCHIVED. About to delete files in attempt $attempt")
		val task = {tryToDeleteFiles(key, soknadarkivschema)}
		val scheduledTime = Instant.now().plusSeconds(0)
		if (tasks[key]?.isBootstrappingTask == true)
			scheduler.scheduleSingleTask(task, scheduledTime)
		else
			scheduler.schedule(task, scheduledTime)
	}

	// Remove task and cancel thread
	fun finishTask(key: String) {

		if (tasks.containsKey(key)) {

			tasks.remove(key)
			loggedTaskStates.remove(key)
			metrics.removeTask()

		} else {
			logger.debug("$key: Already finished")
		}
		MDC.clear()
	}

	//  Keep task. Note that the thread is canceled and new coroutine must be started in order to resume processing
	fun failTask(key: String) {
		if (tasks.containsKey(key)) {

			loggedTaskStates[key] = EventTypes.FAILURE
			updateNoOfFailedMetrics()
			logger.info("$key: Failed task")
			tasks[key]?.isRunningLock?.release()

		} else {
			logger.info("$key: Tried to fail task, but it is already finished")
		}
		MDC.clear()
	}

	internal fun getFailedTasks(): Set<String> {
		return loggedTaskStates.filter { it.value == EventTypes.FAILURE }.keys
	}


	private fun updateNoOfFailedMetrics() {
		metrics.setTasksGivenUpOn(getFailedTasks().size)
	}

	private fun getSecondsToWait(attempt: Int): Long {
		val index = if (attempt < secondsBetweenRetries.size)
			attempt
		else
			secondsBetweenRetries.lastIndex

		return secondsBetweenRetries[index]
	}

	private fun tryToArchive(key: String, soknadarkivschema: Soknadarkivschema, attempt: Int) {
		CoroutineScope(Dispatchers.Default).launch {
			MDC.put(MDC_INNSENDINGS_ID, key)
			var nextState: EventTypes? = null
			val timer = metrics.archivingLatencyStart()
			val histogram = metrics.archivingLatencyHistogramStart(soknadarkivschema.arkivtema)
			try {
				checkIfAlreadyArchived(key)
				logger.info("$key: Will now start to fetch files and send to the archive")
				val files = archiverService.fetchFiles(key, soknadarkivschema)

				protectFromShutdownInterruption(applicationState) {
					MDC.put(MDC_INNSENDINGS_ID, key)
					archiverService.archive(key, soknadarkivschema, files)
					nextState = EventTypes.ARCHIVED
				}

				logger.info("$key: Finished sending to the archive")

			} catch (e: ApplicationAlreadyArchivedException) {
				// Log nothing, the Exceptions of this type are supposed to already have been logged
				nextState = EventTypes.ARCHIVED

			} catch (e: ArchivingException) {
				if (attempt >= 3 || attempt >= secondsBetweenRetries.size - 1) {
					// Logging as Error will trigger alerts. Only log as Error after there has been a few failures.
					logger.error(e.message, e)
				}
				nextState = retry(key)
			} catch (e: FilesAlreadyDeletedException) {

				logger.warn(
					"$key: Files deleted, indicating that the application is already archived. " +
						"Will check if application is archived and re-try if not"
				)
				nextState = retry(key)

			} catch (e: Exception) {
				nextState = when (e.cause) {
					is FilesAlreadyDeletedException -> {
						logger.warn(
							"$key: Files deleted, indicating that the application is already archived. " +
								"Will check if application is archived and re-try if not"
						)
						retry(key)
					}

					is ApplicationAlreadyArchivedException -> {
						logger.warn("$key: Application already archived. Will continue without archiving")
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
				MDC.clear()
				metrics.endTimer(timer)
				metrics.endHistogramTimer(histogram)
				metrics.setNumberOfAttachmentHistogram(
					soknadarkivschema.mottatteDokumenter.size.toDouble(),
					soknadarkivschema.arkivtema
				)
				if (nextState != null && tasks[key] != null) {
					setStateChange(key, nextState!!, soknadarkivschema, tasks[key]?.count!!)
				}
			}
		}
	}

	private fun checkIfAlreadyArchived(key: String) {
		val journalpost = try {
			 safService.hentJournalpostGittInnsendingId(key)
		} catch (ex: Exception) {
			logger.warn("$key: Call to SAF service failed", ex)
			return
		}
		if (journalpost != null) {
			val archivingdetails = "Already archived journalpostId=${journalpost.journalpostId}, opprettet=${journalpost.datoOpprettet}"
			logger.info("$key: $archivingdetails")
			throw ApplicationAlreadyArchivedException("$key: $archivingdetails")
		}
	}

	private fun tryToDeleteFiles(key: String, soknadarkivschema: Soknadarkivschema) {
		try {
			MDC.put(MDC_INNSENDINGS_ID, key)
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
			MDC.clear()
		}
	}

	private fun retry(key: String): EventTypes? {
		if (tasks[key] == null)
			return null

		val count = incrementRetryCount(key)
		return if (count >= secondsBetweenRetries.size) {
			logger.warn("$key: publiser meldingsvarsling til avsender")
			archiverService.createMessage(key, "**Archiving: FAILED")
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
