package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.TopologyTestDriverTests
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createAppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.schemaRegistryScope
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
class StateRecreationTests : TopologyTestDriverTests() {

	private val appConfiguration = createAppConfiguration()
	private val archiverService = mock<ArchiverService>()
	private val archiverScheduler = mock<ThreadPoolTaskScheduler>()
	private val taskListService = TaskListService(archiverService, appConfiguration, archiverScheduler)

	private val soknadarkivschema = createSoknadarkivschema()

	@BeforeEach
	fun setup() {
		setupKafkaTopologyTestDriver(appConfiguration, taskListService, mock())
	}

	@AfterEach
	fun teardown() {
		closeTestDriver()
		MockSchemaRegistry.dropScope(schemaRegistryScope)
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

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(0)
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

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(1)
	}

	@Test
	fun `Can read Event Log with Event that was started six times`() {
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

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(6)
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

		verifyThatScheduler().wasCalled(1).forKey(key0).withCount(1)
		verifyThatScheduler().wasCalled(1).forKey(key1).withCount(1)
	}

	@Disabled // TODO
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

	@Disabled // TODO
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

	@Disabled // TODO
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

		verifyThatScheduler().wasCalled(1).forKey(key0).withCount(1)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Disabled // TODO
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

		verifyThatScheduler().wasCalled(1).forKey(key0).withCount(1)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log where soknadsarkivschema is missing`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(key to RECEIVED, key to STARTED)

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Disabled // TODO
	@Test
	fun `Can read Event Log where ProcessingEvents are missing`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(0)
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

		verifyThatScheduler().wasCalled(1).forKey(key).withCount(1)
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
		private var key: () -> String = { anyString() }
		private var count: () -> Int = { anyInt() }

		fun wasCalled(times: Int): KeyStep {
			timesCalled = times
			return KeyStep(this)
		}

		fun wasNotCalled() {
			verify()
		}

		fun wasNotCalledForKey(key: String) {
			this.key = { eq(key) }
			verify()
		}

		inner class KeyStep(private val schedulerVerifier: SchedulerVerifier) {
			fun forKey(key: String): CountStep {
				schedulerVerifier.key = { eq(key) }
				return CountStep(schedulerVerifier)
			}
		}

		inner class CountStep(private val schedulerVerifier: SchedulerVerifier) {
			fun withCount(count: Int) {
				schedulerVerifier.count = { eq(count) }
				schedulerVerifier.verify()
			}
		}

		private fun verify() {
			val value = if (timesCalled > 0) {
				{ eq(soknadarkivschema) }
			} else {
				{ any() }
			}
			TimeUnit.SECONDS.sleep(2) // TODO
			val captor = argumentCaptor<Runnable>()
			verify(archiverScheduler, times(timesCalled)).schedule(capture(captor), any<Instant>())

			val capturedValues = captor.allValues.size
			assertEquals(timesCalled, capturedValues)
			if (capturedValues > 0) {
				captor.firstValue.run()

				verify(archiverService, times(1)).archive(key.invoke(), value.invoke())
			}
		}

		inline fun <reified T : Runnable> argumentCaptor(): ArgumentCaptor<T> = ArgumentCaptor.forClass(T::class.java)
	}
}
