package no.nav.soknad.arkivering.soknadsarkiverer

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
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
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@Import(ContainerizedKafka::class)
class IntegrationTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockBean
	private lateinit var collectorRegistry: CollectorRegistry

	@Autowired
	private lateinit var appConfiguration: AppConfiguration
	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>

	private val fileId = UUID.randomUUID().toString()


	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

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

		putDataOnKafkaTopic(createSoknadarkivschema())
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(2)
	}

	@Test
	fun `Sending in invalid data will not cause processing`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this string is not deserializable")

		TimeUnit.SECONDS.sleep(1)
		verifyMockedPostRequests(0, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Poison pill followed by proper event -- One event discarded, one to Joark`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this is not deserializable")
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
	}


	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		val url = appConfiguration.config.filestorageUrl + ".*"
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
		val topic = appConfiguration.kafkaConfig.inputTopic
		return putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun putDataOnTopic(key: String, value: String, headers: Headers = RecordHeaders()): RecordMetadata {
		val topic = appConfiguration.kafkaConfig.inputTopic
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
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}
}
