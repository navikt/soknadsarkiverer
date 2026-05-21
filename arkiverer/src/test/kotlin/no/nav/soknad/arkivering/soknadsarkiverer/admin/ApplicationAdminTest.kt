package no.nav.soknad.arkivering.soknadsarkiverer.admin

import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.token.support.spring.test.EnableMockOAuth2Server
import com.ninjasquad.springmockk.SpykBean
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.ARCHIVED
import no.nav.soknad.arkivering.avroschemas.EventTypes.FAILURE
import no.nav.soknad.arkivering.avroschemas.EventTypes.FINISHED
import no.nav.soknad.arkivering.avroschemas.EventTypes.RECEIVED
import no.nav.soknad.arkivering.avroschemas.EventTypes.STARTED
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListProperties
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.util.serializeMsg
import no.nav.soknad.arkivering.soknadsarkiverer.utils.InnsendingTopicMsgBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.TestDokument
import no.nav.soknad.arkivering.soknadsarkiverer.utils.filestorageContent
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockFilestorageIsWorking
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockJoarkIsWorking
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockJoarkRespondsAfterAttempts
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockSafRequest_notFound
import no.nav.soknad.arkivering.soknadsarkiverer.utils.setupMockedNetworkServices
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates
import java.lang.Thread.sleep
import no.nav.soknad.arkivering.soknadsarkiverer.Constants.BEARER
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.ContainerizedKafka
import no.nav.soknad.arkivering.soknadsarkiverer.utils.KafkaListener
import no.nav.soknad.arkivering.soknadsarkiverer.utils.Key
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import no.nav.soknad.arkivering.soknadsarkiverer.utils.stopMockedNetworkServices
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach


@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnableMockOAuth2Server(port = 1888)
@AutoConfigureWireMock(port = 5490)
class ApplicationAdminTest : ContainerizedKafka() {

	@MockitoBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Autowired
	lateinit var mockOAuth2Server: MockOAuth2Server

	@Autowired
	private lateinit var kafkaConfig: KafkaConfig

	@Autowired
	lateinit var restTemplate: TestRestTemplate

	@Autowired
	private lateinit var taskListService: TaskListService

	@SpykBean
	lateinit var metrics: ArchivingMetrics

	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String

	@Value("\${saf.path}")
	private lateinit var safUrl: String

	@Autowired
	private lateinit var tasklistProperties: TaskListProperties

	@Value("\${application.mocked-port-for-external-services}")

	private val portToExternalServices: Int? = null
	private var maxNumberOfAttempts by Delegates.notNull<Int>()
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>
	private lateinit var kafkaListener: KafkaListener
	private lateinit var kafkaNologinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaloggedinTopicProducer: KafkaProducer<String, String>

	@BeforeAll
	fun setupKafkaProducersAndListeners() {
		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap()
			.also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })
		kafkaNologinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})
		kafkaloggedinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})

		kafkaListener = KafkaListener(kafkaConfig)
	}

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(
			portToExternalServices!!,
			journalPostUrl,
			"/innsendte/v1/files",
			safUrl,
		)

		maxNumberOfAttempts = tasklistProperties.secondsBetweenRetries.size
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
		metrics.unregister()
		taskListService.clearLoggedTaskStates()
	}

	@AfterAll
	fun stopKafkaConsumers() {
		kafkaListener.close()
	}

	@Test
	fun `happy case - application is archieved in after retry`() {

		// Given
		val token = mockOAuth2Server.issueToken("test-client-id").serialize()

		val innsendingsId = failArchiving()
		sleep(2000)
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = innsendingsId)

		// When
		val response = restTemplate.exchange(
			"http://localhost:8080/admin/rerun/$innsendingsId",
			HttpMethod.POST,
			HttpEntity(null, createHeaders(token, MediaType.APPLICATION_JSON )),
			String::class.java
		)

		// Then
		assert(response.statusCode.is2xxSuccessful())

		verifyProcessingEvents(
			innsendingsId, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 1
			)
		)

	}


	private fun failArchiving(): String {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(maxNumberOfAttempts)
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(loggedInMsg)

		verifyProcessingEvents(
			loggedInMsg.innsendingsId, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)

		return key
	}

	fun createHeaders(token: String?, contentType: MediaType): HttpHeaders {
		val headers = HttpHeaders()
		headers.contentType = contentType
		if (token != null) {
			headers.add(HttpHeaders.AUTHORIZATION, "$BEARER$token")
		}
		return headers
	}

	private fun putDataOnKafkaTopic(
		key: String,
		value: InnsendingTopicMsg,
	): RecordMetadata {
		return if (value.kanal == "NAV_NO") {
			putDataOnTopic(key, serializeMsg(value), RecordHeaders(), topic = kafkaConfig.topics.loggedinSubmissionTopic, kafkaloggedinTopicProducer)
		} else {
			putDataOnTopic(key, serializeMsg(value), RecordHeaders(), topic = kafkaConfig.topics.nologinSubmissionTopic, kafkaNologinTopicProducer)
		}
	}

	private fun putDataOnKafkaTopic(		value: InnsendingTopicMsg) : RecordMetadata {
		return putDataOnKafkaTopic(value.innsendingsId, value)
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

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "mock://mocked-scope"
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}


	private fun verifyProcessingEvents(key: Key, eventTypeAndCount: Map<EventTypes, Int>) {
		eventTypeAndCount.forEach { (expectedEventType: EventTypes, expectedCount: Int) ->

			val seenEventTypes = {
				kafkaListener.getProcessingEvents()
					.filter { it.key == key }
					.filter { it.value.type == expectedEventType }
					.size
			}

			loopAndVerify(expectedCount, seenEventTypes)
			{
				assertEquals(
					expectedCount, seenEventTypes.invoke(),
					"Expected to see $expectedCount $expectedEventType"
				)
			}
		}
	}

	private infix fun <A> A.hasCount(count: Int) = this to count

}
