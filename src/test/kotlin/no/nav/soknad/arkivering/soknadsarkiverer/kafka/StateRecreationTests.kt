package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.service.SchedulerService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.springframework.test.context.ActiveProfiles
import java.util.*

@ActiveProfiles("test")
class StateRecreationTests : TopologyTestDriverTests() {

	private val appConfiguration = createAppConfiguration()
	private val schedulerService = mock<SchedulerService>()
	private val taskListService = TaskListService(schedulerService)

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


	private fun verifyThatScheduler() = SchedulerVerifier(schedulerService, soknadarkivschema)
}

private class SchedulerVerifier(private val schedulerService: SchedulerService, private val soknadarkivschema: Soknadarkivschema) {
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

	class KeyStep(private val schedulerVerifier: SchedulerVerifier) {
		fun forKey(key: String): CountStep {
			schedulerVerifier.key = { eq(key) }
			return CountStep(schedulerVerifier)
		}
	}

	class CountStep(private val schedulerVerifier: SchedulerVerifier) {
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
		verify(schedulerService, times(timesCalled)).schedule(key.invoke(), value.invoke(), count.invoke())
	}
}
