package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Waits until archiving of [key] has ended with FINISHED [times] times, the listener has read
 * everything published until then, and the application has handled FINISHED. FINISHED is the last
 * record the application publishes for a successful application, so exact counts and absence can
 * be asserted afterwards. When the application has handled FINISHED, it has removed the task and
 * lowered the task gauge, so the next record for [key] is not part of the old task.
 */
fun KafkaListener.awaitFinished(key: Key, times: Int = 1) {
	awaitProcessingEvent(key, EventTypes.FINISHED.name, times)
	awaitCaughtUp()
	awaitApplicationCaughtUp()
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
