package no.nav.soknad.arkivering.soknadsarkiverer

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
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

@Disabled // TODO: Enable when JournalpostClient publishes to Joark
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(EmbeddedKafkaBrokerConfig::class)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@EmbeddedKafka(topics = ["privat-soknadInnsendt-v1-default", "privat-soknadInnsendt-processingEventLog-v1-default", "privat-soknadInnsendt-messages-v1-default"])
class IntegrationTests {

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Value("\${spring.embedded.kafka.brokers}")
	private val kafkaBrokers: String? = null

	@Autowired
	private lateinit var appConfiguration: AppConfiguration
	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>

	private val uuid = UUID.randomUUID().toString()


	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		assertEquals(kafkaBrokers, appConfiguration.kafkaConfig.servers, "The Kafka bootstrap server property is misconfigured!")

		kafkaProducer = KafkaProducer(kafkaConfigMap())
		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap().also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
	}


	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())
		putDataOnKafkaTopic(createSoknadarkivschema())

		TimeUnit.SECONDS.sleep(1)
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(2)
	}

	@Test
	fun `Sending in invalid data will not cause processing`() {
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(invalidData)

		TimeUnit.SECONDS.sleep(1)
		verifyMockedPostRequests(0, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Poison pill followed by proper event -- One event discarded, one to Joark`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this is not deserializable")
		putDataOnKafkaTopic(createSoknadarkivschema())

		TimeUnit.SECONDS.sleep(1)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
	}


	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, appConfiguration.config.filestorageUrl.replace("?", "\\?") + ".*")
	}

	private fun createSoknadarkivschema() = createSoknadarkivschema(uuid)


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
