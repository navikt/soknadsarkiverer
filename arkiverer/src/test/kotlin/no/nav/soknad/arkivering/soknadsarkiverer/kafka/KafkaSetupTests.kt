package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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

	@Test
	fun `Failed Kafka Streams startup leaves application unready`() {
		val applicationState = ApplicationState(alive = true, ready = false)
		val streamsSetup = mockk<KafkaStreamsSetup>()
		every { streamsSetup.setupKafkaStreams("test-application_v2") } throws IllegalStateException("Startup failed")

		assertThrows(IllegalStateException::class.java) {
			kafkaSetup(applicationState, streamsSetup).initializeKafka(bootstrap = {})
		}

		assertFalse(applicationState.ready)
	}

	@Test
	fun `Production initializes the managed Kafka Streams with the existing application id`() {
		val applicationState = ApplicationState(alive = true, ready = false)
		val streamsSetup = mockk<KafkaStreamsSetup>(relaxed = true)

		kafkaSetup(applicationState, streamsSetup).initializeKafka(bootstrap = {})

		verify(exactly = 1) { streamsSetup.setupKafkaStreams("test-application_v2") }
		assertTrue(applicationState.ready)
	}

	private fun kafkaSetup(
		applicationState: ApplicationState,
		streamsSetup: KafkaStreamsSetup = mockk(relaxed = true)
	): KafkaSetup {
		val scheduler = mockk<Scheduler>().also {
			every { it.scheduleSingleTask(any(), any()) } just Runs
		}
		val kafkaConfig = mockk<KafkaConfig>().also {
			every { it.delayBeforeKafkaInitialization } returns "0"
			every { it.applicationId } returns "test-application"
		}

		return KafkaSetup(
			applicationState = applicationState,
			taskListService = mockk<TaskListService>(relaxed = true),
			kafkaStreamsSetup = streamsSetup,
			scheduler = scheduler,
			metrics = mockk<ArchivingMetrics>(relaxed = true),
			kafkaConfig = kafkaConfig
		)
	}
}
