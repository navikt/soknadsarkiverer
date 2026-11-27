package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.token.support.spring.test.EnableMockOAuth2Server
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaSetupTest
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import kotlin.properties.Delegates

/**
 * Shared setup for tests of the archiving flow. The application runs against a real Kafka broker,
 * WireMock stands in for Joark, SAF and innsending-api, and the mock OAuth2 server issues tokens for
 * the admin API. Every subclass has the same Spring configuration, so they can share a context once
 * the broker is shared. Ports are chosen at runtime.
 */
@ActiveProfiles("test")
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["spring.main.allow-bean-definition-overriding=true"]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnableMockOAuth2Server
@AutoConfigureWebTestClient
abstract class ArchivingFlowTest : ContainerizedKafka() {

	@MockitoBean
	protected lateinit var prometheusRegistry: PrometheusRegistry

	@MockitoBean
	protected lateinit var azureJwtDecoder: JwtDecoder

	// Spies, so that a test can inject a failure that WireMock cannot produce.
	@MockitoSpyBean
	protected lateinit var kafkaPublisher: KafkaPublisher

	@MockitoSpyBean
	protected lateinit var archiverService: ArchiverService

	@Autowired
	protected lateinit var kafkaConfig: KafkaConfig

	@Autowired
	private lateinit var kafkaSetup: KafkaSetupTest

	@Autowired
	protected lateinit var taskListService: TaskListService

	@Autowired
	protected lateinit var objectMapper: ObjectMapper

	@Autowired
	protected lateinit var metrics: ArchivingMetrics

	@Autowired
	protected lateinit var tasklistProperties: TaskListProperties

	@Autowired
	protected lateinit var webTestClient: WebTestClient

	@Autowired
	protected lateinit var mockOAuth2Server: MockOAuth2Server

	protected lateinit var kafkaListener: KafkaListener
	private lateinit var nologinTopicProducer: KafkaProducer<String, String>
	protected lateinit var loggedinTopicProducer: KafkaProducer<String, String>

	protected var maxNumberOfAttempts by Delegates.notNull<Int>()

	protected val journalPostUrl = JOURNALPOST_PATH
	protected val safUrl = SAF_PATH

	companion object {
		private const val JOURNALPOST_PATH = "/rest/journalpostapi/v1/journalpost"
		private const val SAF_PATH = "/graphql"

		/**
		 * Started before Spring loads the context, so the application can be given the port.
		 * WireMockExtension starts its server in beforeAll, after a PER_CLASS test instance and its
		 * context have been created. The server lives as long as the test JVM.
		 */
		@JvmStatic
		protected val wireMock: WireMockServer = WireMockServer(
			wireMockConfig()
				.dynamicPort()
				.notifier(ConsoleNotifier(false))
				.asynchronousResponseEnabled(false)
		).also {
			it.start()
			Runtime.getRuntime().addShutdownHook(Thread(it::stop))
		}

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("joark.host") { wireMock.baseUrl() }
			registry.add("joark.journal-post") { JOURNALPOST_PATH }
			registry.add("innsendingapi.host") { wireMock.baseUrl() }
			registry.add("saf.url") { wireMock.baseUrl() }
			registry.add("saf.path") { SAF_PATH }
		}
	}

	@BeforeAll
	fun startKafkaListener() {
		kafkaListener = KafkaListener(kafkaConfig, kafkaSetup.groupId)
	}

	@AfterAll
	fun closeKafkaListener() {
		if (::kafkaListener.isInitialized) kafkaListener.close()
	}

	@BeforeEach
	fun setupFlowTest() {
		wireMock.resetAll()
		setupMockedNetworkServices(wireMock, journalPostUrl, "/innsendte/v1/files", safUrl)
		nologinTopicProducer = KafkaProducer(stringProducerConfig())
		loggedinTopicProducer = KafkaProducer(stringProducerConfig())
		maxNumberOfAttempts = tasklistProperties.secondsBetweenRetries.size
	}

	@AfterEach
	fun teardownFlowTest() {
		assertAll(
			{ wireMock.resetAll() },
			{ if (::nologinTopicProducer.isInitialized) nologinTopicProducer.close(Duration.ofSeconds(30)) },
			{ if (::loggedinTopicProducer.isInitialized) loggedinTopicProducer.close(Duration.ofSeconds(30)) },
			{ metrics.unregister() },
			{ taskListService.clearLoggedTaskStates() }
		)
	}

	/** Publishes [message] on the submission topic for its channel, as soknadsmottaker does. */
	protected fun putDataOnKafkaTopic(message: InnsendingTopicMsg) {
		val (topic, producer) = when (message.kanal) {
			"NAV_NO" -> kafkaConfig.topics.loggedinSubmissionTopic to loggedinTopicProducer
			"NAV_NO_UINNLOGGET" -> kafkaConfig.topics.nologinSubmissionTopic to nologinTopicProducer
			else -> throw IllegalArgumentException("Unknown kanal: ${message.kanal}")
		}
		putDataOnTopic(message.innsendingsId, objectMapper.writeValueAsString(message), RecordHeaders(), topic, producer)
	}

	protected fun verifyProcessingEvents(key: Key, eventTypeAndCount: Map<EventTypes, Int>) {
		eventTypeAndCount.forEach { (expectedEventType, expectedCount) ->
			val seen = kafkaListener.getProcessingEvents().count { it.key == key && it.value.type.name == expectedEventType.name }
			assertEquals(expectedCount, seen, "Expected to see $expectedCount $expectedEventType")
		}
	}

	protected fun verifyMessageStartsWith(key: Key, messageAndCount: Map<String, Int>) {
		messageAndCount.forEach { (expectedMessage, expectedCount) ->
			val seen = kafkaListener.getMessages().count { it.key == key && it.value.startsWith(expectedMessage) }
			assertEquals(expectedCount, seen, "Expected to see $expectedCount messages starting with '$expectedMessage'")
		}
	}

	protected fun verifyArkiveringstilbakemeldingStartsWith(key: Key, messageAndCount: Map<String, Int>) {
		messageAndCount.forEach { (expectedMessage, expectedCount) ->
			val seen = kafkaListener.getArkiveringstilbakemeldinger().count { it.key == key && it.value.startsWith(expectedMessage) }
			assertEquals(expectedCount, seen, "Expected to see $expectedCount arkiveringstilbakemeldinger starting with '$expectedMessage'")
		}
	}

	protected fun verifyKafkaMetric(key: Key, metricAndCount: Map<String, Int>) {
		metricAndCount.forEach { (expectedMetric, expectedCount) ->
			val seen = kafkaListener.getMetrics().count { it.key == key && it.value.action == expectedMetric }
			assertEquals(expectedCount, seen, "Expected to see $expectedCount '$expectedMetric'")
		}
	}

	/** Prometheus metrics are updated by the application's threads, so wait for the expected value. */
	protected fun verifyArchivingMetrics(expected: Double, actual: () -> Double, message: String? = null) {
		loopAndVerify(expected.toInt(), { actual().toInt() }) { assertEquals(expected.toInt(), actual().toInt(), message) }
	}

	private fun stringProducerConfig(): Map<String, Any> = mapOf(
		ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaConfig.brokers,
		ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
		ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java
	)
}
