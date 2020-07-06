package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Service
class TaskListService(private val schedulerService: SchedulerService, private val archiverScheduler: ThreadPoolTaskScheduler) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val tasks = mutableMapOf<String, Task>()

	fun addOrUpdateTask(key: String, value: Soknadarkivschema, count: Int) {
		val task = tasks[key]

		if (count == 0 || task == null) { // Create new task
			tasks[key] = Task(value, count, Semaphore(1).also { it.acquire() })

			archiverScheduler.schedule({ unlock(key) }, Instant.now())
			logger.info("Created task with key '$key' and count $count")

		} else {
			tasks[key] = Task(value, count, task.lock)

			logger.info("Updated task with key '$key' and count $count")
		}
		archiverScheduler.schedule({ schedule(key) }, Instant.now())
	}

	private fun schedule(key: String) {
		val task = tasks[key]
		if (task != null) {
			logger.info("Trying to lock for $key")
			task.lock.acquire()
			logger.info("Managed to lock for $key")

			if (tasks.containsKey(key))
				schedulerService.schedule(key, tasks[key]!!.value, tasks[key]!!.count, this)
		}
	}

	fun unlock(key: String) {
		val task = tasks[key]
		if (task != null) {
			TimeUnit.SECONDS.sleep(1)
			logger.info("Unlocking $key")
			task.lock.release()
			logger.info("Unlocked $key")
		}
	}

	fun finishTask(key: String) {
		if (tasks.containsKey(key)) {
			tasks.remove(key)
			logger.info("Finished task with key '$key'")

		} else {
			logger.error("Attempted to finish task with key '$key', but no such task exists!")
		}
	}

	fun listTasks() = tasks.mapValues { (_, task) -> task.value to task.count }
}

private data class Task(val value: Soknadarkivschema, val count: Int, val lock: Semaphore)
