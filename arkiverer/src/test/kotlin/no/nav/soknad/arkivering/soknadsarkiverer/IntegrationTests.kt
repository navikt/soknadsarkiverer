package no.nav.soknad.arkivering.soknadsarkiverer

import com.github.tomakehurst.wiremock.http.RequestMethod
import com.ninjasquad.springmockk.MockkBean
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilestorageProperties
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
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
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationTests : ContainerizedKafka() {

	@MockBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Autowired
	private lateinit var filestorageProperties: FilestorageProperties

	@Autowired
	private lateinit var kafkaConfig: KafkaConfig

	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String

	@Value("\${saf.path}")
	private lateinit var safUrl: String
	private lateinit var kafkaProducer: KafkaProducer<String, Soknadarkivschema>
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>

	private val fileId = UUID.randomUUID().toString()

	@Autowired
	private lateinit var metrics: ArchivingMetrics


	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, journalPostUrl, filestorageProperties.files, safUrl)

		kafkaProducer = KafkaProducer(kafkaConfigMap())
		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
		metrics.unregister()
	}

	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		val initialRequests = countRequests(journalPostUrl, RequestMethod.POST)
		val soknadarkivschema = createSoknadarkivschema()
		mockSafRequest_notFound(innsendingsId = soknadarkivschema.behandlingsid)
		putDataOnKafkaTopic(soknadarkivschema)
		val soknadarkivschema2 = createSoknadarkivschema()
		mockSafRequest_notFound(innsendingsId = soknadarkivschema2.behandlingsid)
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyMockedPostRequests(initialRequests + 2, journalPostUrl)
	}

	@Test
	fun `Sending in invalid data will not cause processing`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this string is not deserializable")

		TimeUnit.SECONDS.sleep(1)
		verifyMockedPostRequests(0, journalPostUrl)
	}

	@Test
	fun `Poison pill followed by proper event -- One event discarded, one to Joark`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this is not deserializable")
		val soknadarkivschema = createSoknadarkivschema()
		mockSafRequest_notFound(innsendingsId = soknadarkivschema.behandlingsid)
		putDataOnKafkaTopic(soknadarkivschema)

		verifyMockedPostRequests(1, journalPostUrl)
	}

	@Test
	fun `Application not sent to Joark if it is already archived`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		val soknadarkivschema = createSoknadarkivschema()
		mockSafRequest_found(innsendingsId = soknadarkivschema.behandlingsid)
		putDataOnKafkaTopic(soknadarkivschema)

		verifyMockedPostRequests(0, journalPostUrl)
	}

	@Test
	fun `Application sent to Joark if error checking SAF`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		val soknadarkivschema = createSoknadarkivschema()
		mockSafRequest_error(innsendingsId = soknadarkivschema.behandlingsid)
		putDataOnKafkaTopic(soknadarkivschema)

		verifyMockedPostRequests(1, journalPostUrl)
	}

	private fun createSoknadarkivschema() = createSoknadarkivschema(fileId)


	private fun putDataOnKafkaTopic(soknadarkivschema: Soknadarkivschema) {
		putDataOnTopic(UUID.randomUUID().toString(), soknadarkivschema)
	}

	private fun putDataOnKafkaTopic(badData: String) {
		putDataOnTopic(UUID.randomUUID().toString(), badData)
	}

	private fun putDataOnTopic(
		key: String,
		value: Soknadarkivschema,
		headers: Headers = RecordHeaders()
	): RecordMetadata {
		val topic = kafkaConfig.topics.mainTopic
		return putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun putDataOnTopic(key: String, value: String, headers: Headers = RecordHeaders()): RecordMetadata {
		val topic = kafkaConfig.topics.mainTopic
		return putDataOnTopic(key, value, headers, topic, kafkaProducerForBadData)
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
}
