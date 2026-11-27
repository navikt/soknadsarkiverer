package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Waits until archiving of [key] has ended with FINISHED [times] times, and the listener has read
 * everything published until then. FINISHED is the last record the application publishes for a
 * successful application, so exact counts and absence can be asserted afterwards.
 */
fun KafkaListener.awaitFinished(key: Key, times: Int = 1) {
	awaitProcessingEvent(key, EventTypes.FINISHED.name, times)
	awaitCaughtUp()
}

/**
 * Waits until the application has given up on [key], and the listener has read everything
 * published until then. The first FAILURE event comes before the failure feedback and its retries.
 * [TaskListService.isFailureStateFinished] becomes true when that work has ended.
 */
fun KafkaListener.awaitFailureHandled(key: Key, taskListService: TaskListService) {
	loopAndVerify(1, { if (taskListService.isFailureStateFinished(key)) 1 else 0 }) {
		assertTrue(taskListService.isFailureStateFinished(key), "Expected the application to give up on $key")
	}
	awaitCaughtUp()
}

/**
 * Waits until the application has handled the FINISHED event for [key] and removed its task.
 * Until then, a new record for [key] is handled as part of the ongoing task, not as a new one.
 */
fun TaskListService.awaitTaskRemoved(key: Key) {
	loopAndVerify(0, { listTasks(key).size }) {
		assertTrue(listTasks(key).isEmpty(), "Expected the task for $key to be removed")
	}
}
