package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import org.slf4j.LoggerFactory

class TaskListService(private val schedulerService: SchedulerService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val tasks = mutableMapOf<String, Task>()

	fun createTask(key: String, value: Soknadarkivschema, count: Int) {
		if (!tasks.containsKey(key)) {
			tasks[key] = Task(value, count)
			schedulerService.schedule(key, value, count)
			logger.debug("Created task with key '$key' and count $count")
		} else {
			logger.error("Attempted to create task with key '$key', but task already exists!")
		}
	}

	fun updateTaskCount(key: String, newCount: Int) {
		if (tasks.containsKey(key)) {
			tasks[key]!!.count = newCount
			logger.debug("Updated task with key '$key' to now have count $newCount")
		} else {
			logger.error("Attempted to update task with key '$key', but no such task exists!")
		}
	}

	fun finishTask(key: String) {
		if (tasks.containsKey(key)) {
			tasks.remove(key)
			logger.debug("Finished task with key '$key'")
		} else {
			logger.error("Attempted to finish task with key '$key', but no such task exists!")
		}
	}

	fun listTasks() = tasks.mapValues { (_, task) -> task.value to task.count }
}

data class Task(val value: Soknadarkivschema, var count: Int)
