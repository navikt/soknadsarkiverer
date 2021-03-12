package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.prometheus.client.CollectorRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.streams.StreamsBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.RandomAccess

@ActiveProfiles("test")
class StateRecreationTests : TopologyTestDriverTests() {

	private val appConfiguration = createAppConfiguration()
	private val archiverService = mock<ArchiverService>()
	private val scheduler = mock<Scheduler>()
	private val kafkaPublisher = mock<KafkaPublisher>()
	private val metrics = ArchivingMetrics(CollectorRegistry.defaultRegistry)
	private val taskListService = TaskListService(archiverService, appConfiguration, scheduler, metrics, kafkaPublisher)

	private val soknadarkivschema = createSoknadarkivschema()

	@BeforeEach
	fun setup() {

		setupKafkaTopologyTestDriver()
			.withAppConfiguration(appConfiguration)
			.withTaskListService(taskListService)
			.runScheduledTasksOnScheduling(scheduler)
			.setup(metrics)
	}

	@AfterEach
	fun teardown() {
		closeTestDriver()
		MockSchemaRegistry.dropScope(schemaRegistryScope)
		metrics.unregister()
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
	fun `Can read Event Log with Finished Event - will not reattempt`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to FINISHED,
			key to RECEIVED
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
			key to FINISHED,
			key to RECEIVED,
			key to STARTED
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
			key1 to ARCHIVED,
			key1 to FINISHED
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key0)
		verifyThatScheduler().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log with mixed order of events`() {
		val key0 = UUID.randomUUID().toString() // ff96c32f-0ead-44cf-af40-b2ddc48a3e53
		val key1 = UUID.randomUUID().toString() // 6c738efd-5c02-4f54-b93c-d906c7aedd4f
		val key2 = UUID.randomUUID().toString() // ead67f86-1540-4a13-9857-8069d142aa7c

		publishSoknadsarkivschemas(key0, key1, key2)
		publishProcessingEvents(
			key1 to RECEIVED,
			key0 to RECEIVED,
			key1 to STARTED,
			key2 to RECEIVED,
			key0 to STARTED,
			key2 to STARTED,
			key1 to ARCHIVED,
			key0 to ARCHIVED,
			key1 to FINISHED,
			key0 to FAILURE
		)

		recreateState()

		verifyThatScheduler().wasCalled(1).forKey(key2)
		verifyThatScheduler().wasNotCalledForKey(key0)
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

	@Test
	fun `Process events, simulate upstart with all received and archived events - none should be scheduled`() {
		val size = 10
		val keyList = MutableList(size) { _ -> UUID.randomUUID().toString()}

		keyList.forEach {key -> publishSoknadsarkivschemas(key)}

		keyList.forEach {key -> publishProcessingEvents(key to RECEIVED, 	key to STARTED, key to ARCHIVED, key to FINISHED)}

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	@Test
	fun `Process events, simulate upstart with some FINISHED and FAILURE events - none should be scheduled`() {
		val size = 10
		val keyList = MutableList(size) { _ -> UUID.randomUUID().toString()}

		keyList.forEach {key -> publishSoknadsarkivschemas(key)}

		keyList.forEach {key -> publishProcessingEvents(key to RECEIVED, 	key to STARTED, key to ARCHIVED, randomFailureOrFinished(key))}

		recreateState()

		verifyThatScheduler().wasNotCalled()
	}

	private fun randomFailureOrFinished(key: String): Pair<String, EventTypes> {
		val rand = (1..1000).random()
		if (rand>600)
			return key to FAILURE
		else
			return key to FINISHED
	}

	private fun publishSoknadsarkivschemas(vararg keys: String) {
		keys.forEach { putDataOnInputTopic(it, soknadarkivschema) }
	}

	private fun publishProcessingEvents(vararg keysAndEventTypes: Pair<String, EventTypes>) {
		keysAndEventTypes.forEach { (key, eventType) -> putDataOnProcessingTopic(key, ProcessingEvent(eventType)) }
	}

	private fun recreateState() {
		KafkaConfig(appConfiguration, taskListService, mock(), metrics).modifiedKafkaStreams(StreamsBuilder())
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
			val value = if (timesCalled > 0) soknadarkivschema else null

			val getInvocations = {
				mockingDetails(archiverService)
					.invocations.stream()
					.filter { if (key == null) true else it.arguments[0] == key }
					.filter { if (value == null) true else it.arguments[1] == value }
					.count().toInt()
			}

			loopAndVerify(if (timesCalled == 0) 0 else timesCalled + 2, getInvocations) // ArchiverService will be called 3 timer when running through state started and archived

			verify(scheduler, atLeast(timesCalled)).schedule(any(), any())
		}
	}
}
