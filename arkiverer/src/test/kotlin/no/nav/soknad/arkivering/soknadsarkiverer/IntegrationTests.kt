package no.nav.soknad.arkivering.soknadsarkiverer

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.ninjasquad.springmockk.MockkBean
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilestorageProperties
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationTests : ContainerizedKafka() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var collectorRegistry: CollectorRegistry

	@Autowired
	private lateinit var filestorageProperties: FilestorageProperties
	@Autowired
	private lateinit var kafkaConfig: KafkaConfig
	@Value("\${joark.journal-post}")
	private lateinit var joarnalPostUrl: String
	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>

	private val fileId = UUID.randomUUID().toString()


	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, joarnalPostUrl, filestorageProperties.files)

		kafkaProducer = KafkaProducer(kafkaConfigMap())
		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap().also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
	}

	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		val initialRequests = countRequests(joarnalPostUrl, RequestMethod.POST)
		putDataOnKafkaTopic(createSoknadarkivschema())
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyMockedPostRequests(initialRequests+2, joarnalPostUrl)
		verifyDeleteRequestsToFilestorage(2)
	}

	@Test
	fun `Sending in invalid data will not cause processing`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this string is not deserializable")

		TimeUnit.SECONDS.sleep(1)
		verifyMockedPostRequests(0, joarnalPostUrl)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Poison pill followed by proper event -- One event discarded, one to Joark`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this is not deserializable")
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyMockedPostRequests(1, joarnalPostUrl)
		verifyDeleteRequestsToFilestorage(1)
	}


	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		val url = filestorageProperties.files + ".*"
		verifyMockedDeleteRequests(expectedCount, url)
	}

	private fun createSoknadarkivschema() = createSoknadarkivschema(fileId)


	private fun putDataOnKafkaTopic(soknadarkivschema: Soknadarkivschema) {
		putDataOnTopic(UUID.randomUUID().toString(), soknadarkivschema)
	}

	private fun putDataOnKafkaTopic(badData: String) {
		putDataOnTopic(UUID.randomUUID().toString(), badData)
	}

	private fun putDataOnTopic(key: String, value: Soknadarkivschema, headers: Headers = RecordHeaders()): RecordMetadata {
		val topic = kafkaConfig.topics.mainTopic
		return putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun putDataOnTopic(key: String, value: String, headers: Headers = RecordHeaders()): RecordMetadata {
		val topic = kafkaConfig.topics.mainTopic
		return putDataOnTopic(key, value, headers, topic, kafkaProducerForBadData)
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

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "mock://mocked-scope"
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
