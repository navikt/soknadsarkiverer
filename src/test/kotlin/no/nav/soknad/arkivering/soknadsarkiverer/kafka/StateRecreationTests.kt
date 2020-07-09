package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.springframework.test.context.ActiveProfiles
import java.util.*

@ActiveProfiles("test")
class StateRecreationTests : TopologyTestDriverTests() {

	private val appConfiguration = createAppConfiguration()
	private val archiverService = mock<ArchiverService>()
	private val scheduler = mock<Scheduler>()
	private val taskListService = TaskListService(archiverService, appConfiguration, scheduler)

	private val soknadarkivschema = createSoknadarkivschema()

	@BeforeEach
	fun setup() {
		setupKafkaTopologyTestDriver(appConfiguration, taskListService, mock())
		runScheduledTaskOnScheduling()
	}

	@AfterEach
	fun teardown() {
		closeTestDriver()
		MockSchemaRegistry.dropScope(schemaRegistryScope)
	}

	private fun runScheduledTaskOnScheduling() {
		val captor = argumentCaptor<() -> Unit>()
		whenever(scheduler.schedule(capture(captor), any()))
			.then { captor.value.invoke() }
	}


	@Test
	fun `Can read empty Event Log`() {
		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was never started`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(key to RECEIVED)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key)
	}

	@Test
	fun `Can read Event Log with Event that was started once`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key)
	}

	@Test
	fun `Can read Event Log with Event that was started six times - will not reattempt`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to STARTED,
			key to STARTED,
			key to STARTED,
			key to STARTED,
			key to STARTED
		)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with two Events that were started once`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key0, key1)
		publishProcessingEvents(
			key0 to RECEIVED,
			key0 to STARTED,

			key1 to RECEIVED,
			key1 to STARTED
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0)
		verifyThatScheduler().wasCalled(1).forKey(key1)
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to STARTED,
			key to FINISHED
		)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished, but in wrong order`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to FINISHED,
			key to STARTED
		)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with one Started and one Finished Event`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key0, key1)
		publishProcessingEvents(
			key0 to RECEIVED,
			key0 to STARTED,

			key1 to RECEIVED,
			key1 to STARTED,
			key1 to FINISHED
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log with mixed order of events`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key0, key1)
		publishProcessingEvents(
			key1 to RECEIVED,
			key0 to RECEIVED,
			key1 to STARTED,
			key0 to STARTED,
			key1 to FINISHED
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log where soknadsarkivschema is missing`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(key to RECEIVED, key to STARTED)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Process events, then another event comes in - only the first ones cause scheduling`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED
		)

		recreateState()

		publishProcessingEvents(key to STARTED)

		verifyThatScheduler().wasCalled(1).forKey(key)
	}


	private fun publishSoknadsarkivschemas(vararg keys: String) {
		val keyValues = keys.map { KeyValue(it, soknadarkivschema) }
		inputTopic.pipeKeyValueList(keyValues)
	}

	private fun publishProcessingEvents(vararg keysAndEventTypes: Pair<String, EventTypes>) {
		val keyValues = keysAndEventTypes.map { (key, eventType) -> KeyValue(key, ProcessingEvent(eventType)) }
		processingEventTopic.pipeKeyValueList(keyValues)
	}

	private fun recreateState() {
		KafkaConfig(appConfiguration, taskListService, mock()).kafkaStreams(StreamsBuilder())
	}


	private fun verifyThatScheduler() = SchedulerVerifier()

	private inner class SchedulerVerifier {
		private var timesCalled = 0
		private var key: String? = null

		fun wasCalled(times: Int): KeyStep {
			timesCalled = times
			return KeyStep()
		}

		fun wasNotCalled() {
			verify()
		}

		fun wasNotCalledForKey(key: String) {
			this.key = key
			verify()
		}

		inner class KeyStep {
			fun forKey(theKey: String) {
				key = theKey
				verify()
			}
		}

		private fun verify() {
			val value = if (timesCalled > 0) {
				soknadarkivschema
			} else {
				null
			}

			val invocations = { mockingDetails(archiverService)
				.invocations.stream()
				.filter { if (key == null) true else it.arguments[0] == key }
				.filter { if (value == null) true else it.arguments[1] == value }
				.count().toInt() }

			loopAndVerify(timesCalled, invocations)

			verify(scheduler, atLeast(timesCalled)).schedule(any(), any())
		}
	}

	private inline fun <reified T> argumentCaptor(): ArgumentCaptor<T> = ArgumentCaptor.forClass(T::class.java)
}
