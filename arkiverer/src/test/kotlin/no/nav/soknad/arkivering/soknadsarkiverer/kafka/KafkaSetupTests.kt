package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KafkaSetupTests {

	@Test
	fun `Application stays unready until scheduled Kafka initialization succeeds`() {
		val applicationState = ApplicationState()
		kafkaSetup(applicationState).setupKafka()

		assertTrue(applicationState.alive)
		assertFalse(applicationState.ready)
	}

	@Test
	fun `Failed bootstrap leaves application unready and skips Kafka Streams startup`() {
		val applicationState = ApplicationState(alive = true, ready = false)
		var streamsStarted = false

		assertThrows(IllegalStateException::class.java) {
			kafkaSetup(applicationState).initializeKafka(
				bootstrap = { throw IllegalStateException("Replay failed") },
				startStreams = { streamsStarted = true }
			)
		}

		assertFalse(streamsStarted)
		assertFalse(applicationState.ready)
	}

	@Test
	fun `Successful bootstrap and Kafka Streams startup marks application ready`() {
		val applicationState = ApplicationState(alive = true, ready = false)

		kafkaSetup(applicationState).initializeKafka(
			bootstrap = {},
			startStreams = {}
		)

		assertTrue(applicationState.ready)
	}

	private fun kafkaSetup(applicationState: ApplicationState): KafkaSetup {
		val scheduler = mockk<Scheduler>().also {
			every { it.scheduleSingleTask(any(), any()) } just Runs
		}
		val kafkaConfig = mockk<KafkaConfig>().also {
			every { it.delayBeforeKafkaInitialization } returns "0"
		}

		return KafkaSetup(
			applicationState = applicationState,
			taskListService = mockk<TaskListService>(relaxed = true),
			kafkaPublisher = mockk<KafkaPublisher>(relaxed = true),
			scheduler = scheduler,
			metrics = mockk<ArchivingMetrics>(relaxed = true),
			kafkaConfig = kafkaConfig
		)
	}
}
