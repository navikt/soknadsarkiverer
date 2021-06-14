package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.*
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.EmbeddedKafkaBrokerConfig
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit

@Disabled
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(EmbeddedKafkaBrokerConfig::class)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@EmbeddedKafka(topics = [kafkaInputTopic, kafkaProcessingTopic, kafkaMessageTopic, kafkaMetricsTopic])
class StateRecreationTests {

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Value("\${spring.embedded.kafka.brokers}")
	private val kafkaBrokers: String? = null

	@Autowired
	private lateinit var appConfiguration: AppConfiguration
	private lateinit var kafkaInputTopicProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProcessingEventProducer: KafkaProducer<String, ProcessingEvent>
	private lateinit var kafkaBootstrapConsumer: KafkaBootstrapConsumer

	private val taskListService = mock<TaskListService>()

	private val soknadarkivschema = createSoknadarkivschema()

	@BeforeAll
	fun setup() {
		assertEquals(kafkaBrokers, appConfiguration.kafkaConfig.servers, "The Kafka bootstrap server property is misconfigured!")
		kafkaInputTopicProducer = KafkaProducer(kafkaConfigMap())
		kafkaProcessingEventProducer = KafkaProducer(kafkaConfigMap())
		kafkaBootstrapConsumer = KafkaBootstrapConsumer(appConfiguration, taskListService)

		kafkaBootstrapConsumer.recreateState() // Other test classes could have left Kafka events on the topics. Consume them before running the tests in this class.
	}

	@AfterEach
	fun teardown() {
		reset(taskListService)
		clearInvocations(taskListService)
	}


	@Test
	fun `Can read empty Event Log`() {
		recreateState()

		verifyThatTaskListService().wasNotCalled()
	}

	@Test
	fun `Can read Event Log with Event that was never started`() {
		val key = UUID.randomUUID().toString()

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


	private fun randomFailureOrFinished(key: String): Pair<String, EventTypes> {
		val rand = (1..1000).random()
		return if (rand > 600)
			key to FAILURE
		else
			key to FINISHED
	}

	private fun publishSoknadsarkivschemas(vararg keys: String) {
		keys.forEach {
			val topic = appConfiguration.kafkaConfig.inputTopic
			putDataOnTopic(it, soknadarkivschema, RecordHeaders(), topic, kafkaInputTopicProducer)
		}
	}

	private fun publishProcessingEvents(vararg keysAndEventTypes: Pair<String, EventTypes>) {
		keysAndEventTypes.forEach { (key, eventType) ->
			val topic = appConfiguration.kafkaConfig.processingTopic
			putDataOnTopic(key, ProcessingEvent(eventType), RecordHeaders(), topic, kafkaProcessingEventProducer)
		}
	}

	private fun <T> putDataOnTopic(key: String, value: T, headers: Headers, topic: String,
																 kafkaProducer: KafkaProducer<String, T>): RecordMetadata {

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
			val value = if (timesCalled > 0) soknadarkivschema else null

			val getInvocations = {
				mockingDetails(taskListService)
					.invocations.stream()
					.filter { if (key == null) true else it.arguments[0] == key }
					.filter { if (value == null) true else it.arguments[1] == value }
					.count().toInt()
			}

			loopAndVerify(timesCalled, getInvocations)

			verify(taskListService, atLeast(timesCalled)).addOrUpdateTask(any(), any(), any())
		}
	}

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "mock://mocked-scope"
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
