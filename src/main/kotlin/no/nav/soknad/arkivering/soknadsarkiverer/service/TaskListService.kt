package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ScheduledFuture

@Service
class TaskListService(private val schedulerService: SchedulerService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val tasks = mutableMapOf<String, Task>()

	fun addOrUpdateTask(key: String, value: Soknadarkivschema, count: Int) {
		val task = schedulerService.schedule(key, value, count)

		if (tasks.containsKey(key))
			tasks[key]!!.scheduledTask.cancel(true)
		tasks[key] = Task(value, count, task)

		logger.info("Created/updated task with key '$key' and count $count")
	}

	fun finishTask(key: String) {
		if (tasks.containsKey(key)) {

			tasks[key]!!.scheduledTask.cancel(true)
			tasks.remove(key)
			logger.info("Finished task with key '$key'")

		} else {
			logger.error("Attempted to finish task with key '$key', but no such task exists!")
		}
	}

	fun listTasks() = tasks.mapValues { (_, task) -> task.value to task.count }
}

private data class Task(val value: Soknadarkivschema, val count: Int, val scheduledTask: ScheduledFuture<*>)
