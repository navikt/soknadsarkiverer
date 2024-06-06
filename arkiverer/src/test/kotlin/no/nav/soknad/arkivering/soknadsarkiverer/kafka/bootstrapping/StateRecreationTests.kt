package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import com.ninjasquad.springmockk.MockkBean
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.mockk.*
import kotlinx.coroutines.runBlocking
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaSetupTest
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilestorageProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.util.*
import java.util.concurrent.TimeUnit


@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class, KafkaConfig::class)
class StateRecreationTests : ContainerizedKafka() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String

	@Value("\${saf.path}")
	private lateinit var safUrl: String

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties


	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var kafkaStreams: KafkaStreams // Mock this so that the real chain isn't run by the tests

	@Autowired
	private lateinit var filestorageProperties: FilestorageProperties

	@Autowired
	private lateinit var kafkaConfig: KafkaConfig

	@Autowired
	private lateinit var kafkaPublisher: KafkaPublisher

	private lateinit var kafkaMainTopicProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProcessingEventProducer: KafkaProducer<String, ProcessingEvent>
	private lateinit var kafkaBootstrapConsumer: KafkaBootstrapConsumer

	@Autowired
	private lateinit var metrics: ArchivingMetrics

	private val safService = mockk<SafServiceInterface>()
	private val scheduler = mockk<Scheduler>().also {
		every { it.schedule(any(), any()) } just Runs
		every { it.scheduleSingleTask(any(), any()) } just Runs
	}
	private val archiverService = mockk<ArchiverService>().also {
		every { runBlocking { it.fetchFiles(any(), any()) } } returns listOf(
			FileInfo(
				"id",
				"content".toByteArray(),
				ResponseStatus.Ok
			)
		)
		every { it.archive(any(), any(), any()) } just Runs
		every { it.deleteFiles(any(), any()) } just Runs
	}
	private val taskListService = mockk<TaskListService>().also {
		every { it.addOrUpdateTask(any(), any(), any(), any()) } just Runs
	}


	private lateinit var kafkaSetup: KafkaSetupTest

	private val soknadarkivschema = createSoknadarkivschema()
	private val fileUuid = UUID.randomUUID().toString()

	@AfterEach
	fun tearDown() {
		metrics.registry.clear()
	}

	@BeforeAll
	fun setup() {
		setupMockedNetworkServices(
			portToExternalServices!! + 1,
			journalPostUrl,
			filestorageProperties.files,
			safUrl
		)
		kafkaMainTopicProducer = KafkaProducer(kafkaConfigMap())
		kafkaProcessingEventProducer = KafkaProducer(kafkaConfigMap())
		kafkaBootstrapConsumer = KafkaBootstrapConsumer(taskListService, kafkaConfig)
		kafkaSetup = KafkaSetupTest(
			applicationState = ApplicationState(alive = true, ready = true),
			taskListService = taskListService,
			kafkaPublisher = kafkaPublisher,
			metrics = metrics,
			kafkaConfig = kafkaConfig
		)


		kafkaBootstrapConsumer.recreateState() // Other test classes could have left Kafka events on the topics. Consume them before running the tests in this class.

	}


	@Test
	fun `Can read empty Event Log`() {
		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was never started`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()
		val soknadsarkivschema = createSoknadarkivschema(key)
		mockSafRequest_notFound(innsendingsId = soknadsarkivschema.behandlingsid)

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(key to RECEIVED)

		recreateState()

		verifyThatTaskListService().wasCalled(1).forKey(key)
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

		verifyThatTaskListService().wasCalled(1).forKey(key)
	}

	@Test
	fun `Can read Event Log with Finished Event - will not reattempt`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to ARCHIVED,
			key to FINISHED
		)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
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

		verifyThatTaskListService().wasCalled(1).forKey(key0)
		verifyThatTaskListService().wasCalled(1).forKey(key1)
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to STARTED,
			key to ARCHIVED,
			key to FINISHED
		)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was started twice and finished, but in wrong order`() {
		val key = UUID.randomUUID().toString()

		publishSoknadsarkivschemas(key)
		publishProcessingEvents(
			key to RECEIVED,
			key to STARTED,
			key to ARCHIVED,
			key to FINISHED,
			key to STARTED
		)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
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

		verifyThatTaskListService().wasCalled(1).forKey(key0)
		verifyThatTaskListService().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log with mixed order of events`() {
		val key0 = UUID.randomUUID().toString()
		val key1 = UUID.randomUUID().toString()
		val key2 = UUID.randomUUID().toString()

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

		verifyThatTaskListService().wasCalled(1).forKey(key2)
		verifyThatTaskListService().wasNotCalledForKey(key0)
		verifyThatTaskListService().wasNotCalledForKey(key1)
	}

	@Test
	fun `Can read Event Log where soknadsarkivschema is missing`() {
		val key = UUID.randomUUID().toString()

		publishProcessingEvents(key to RECEIVED, key to STARTED)

		recreateState()

		verifyThatTaskListService().wasNotCalled()
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

		verifyThatTaskListService().wasCalled(1).forKey(key)
	}

	@Test
	fun `Process events, simulate upstart with all received and archived events - none should be scheduled`() {
		val size = 50
		val keyList = MutableList(size) { UUID.randomUUID().toString() }

		keyList.forEach { key -> publishSoknadsarkivschemas(key) }

		keyList.forEach { key ->
			publishProcessingEvents(
				key to RECEIVED,
				key to STARTED,
				key to ARCHIVED,
				key to FINISHED
			)
		}

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Process events, simulate upstart with some FINISHED and FAILURE events - none should be scheduled`() {
		val size = 40
		val keyList = MutableList(size) { UUID.randomUUID().toString() }

		keyList.forEach { key -> publishSoknadsarkivschemas(key) }

		keyList.forEach { key ->
			publishProcessingEvents(
				key to RECEIVED,
				key to STARTED,
				key to ARCHIVED,
				randomFailureOrFinished(key)
			)
		}

		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	var countFinishedOrFailure: Int = 0

	@Test
	fun `Process events, simulate upstart with some FINISHED and FAILURE and Not Finished events - some should be scheduled`() {
		val size = 40
		val keyList = MutableList(size) { UUID.randomUUID().toString() }

		keyList.forEach { key -> publishSoknadsarkivschemas(key) }

		countFinishedOrFailure = 0
		keyList.forEach { key ->
			publishProcessingEvents(
				key to RECEIVED,
				key to STARTED,
				randomFailureOrFinishedOrStarted(key)
			)
		}

		recreateState()

		verifyThatTaskListService().wasCalled(size - countFinishedOrFailure)
	}


	private fun randomFailureOrFinished(key: String): Pair<String, EventTypes> {
		val rand = (1..1000).random()
		return if (rand > 600)
			key to FAILURE
		else
			key to FINISHED
	}

	private fun randomFailureOrFinishedOrStarted(key: String): Pair<String, EventTypes> {
		val rand = (1..1000).random()
		if (rand > 300) countFinishedOrFailure + 1 else countFinishedOrFailure
		return if (rand > 400)
			key to FAILURE
		else if (rand > 300)
			key to FINISHED
		else
			key to STARTED
	}

	private fun publishSoknadsarkivschemas(vararg keys: String) {
		keys.forEach {
			val topic = kafkaConfig.topics.mainTopic
			putDataOnTopic(it, soknadarkivschema, RecordHeaders(), topic, kafkaMainTopicProducer)
		}
	}

	private fun publishProcessingEvents(vararg keysAndEventTypes: Pair<String, EventTypes>) {
		keysAndEventTypes.forEach { (key, eventType) ->
			val topic = kafkaConfig.topics.processingTopic
			putDataOnTopic(key, ProcessingEvent(eventType), RecordHeaders(), topic, kafkaProcessingEventProducer)
		}
	}

	private fun <T> putDataOnTopic(
		key: String, value: T, headers: Headers, topic: String,
		kafkaProducer: KafkaProducer<String, T>
	): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add(MESSAGE_ID, UUID.randomUUID().toString().toByteArray())
		headers.forEach { producerRecord.headers().add(it) }

		return kafkaProducer
			.send(producerRecord)
			.get(1000, TimeUnit.MILLISECONDS) // Blocking call
	}


	private fun recreateState() {
		kafkaBootstrapConsumer.recreateState()
	}


	private fun verifyThatTaskListService() = TaskListServiceVerifier()

	private inner class TaskListServiceVerifier {
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
			val key = this.key

			if (key == null || timesCalled == 0)
				verify(atLeast = timesCalled) { taskListService.addOrUpdateTask(any(), any(), any(), any()) }
			else
				verify(atLeast = timesCalled) { taskListService.addOrUpdateTask(eq(key), eq(soknadarkivschema), any(), any()) }
		}
	}

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "mock://mocked-scope"
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
