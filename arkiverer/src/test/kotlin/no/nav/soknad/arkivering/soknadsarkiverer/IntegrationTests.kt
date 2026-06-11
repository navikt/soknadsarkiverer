package no.nav.soknad.arkivering.soknadsarkiverer

import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.junit5.WireMockExtension

import com.ninjasquad.springmockk.MockkBean
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.util.serializeMsg
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
class IntegrationTests : ContainerizedKafka() {

	@MockitoBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockkBean(relaxed = true)
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Autowired
	private lateinit var kafkaConfig: KafkaConfig

	@Value("\${joark.journal-post}")
	private lateinit var journalPostUrl: String

	@Value("\${saf.path}")
	private lateinit var safUrl: String

	private lateinit var kafkaNologinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaLoggedinTopicProducer: KafkaProducer<String, String>
	private lateinit var kafkaProducerForBadData: KafkaProducer<String, String>

	private val fileId = UUID.randomUUID().toString()

	@Autowired
	private lateinit var metrics: ArchivingMetrics


	companion object {

		@JvmField
		@RegisterExtension
		val wireMock: WireMockExtension = WireMockExtension.newInstance()
			.configureStaticDsl(true)
			.options(
				wireMockConfig()
					.port(2908)
					.notifier(ConsoleNotifier(true))
					.withRootDirectory("src/test/resources")
					.asynchronousResponseEnabled(false)
			)
			.build()

		@JvmStatic
		@DynamicPropertySource
		fun properties(reg: DynamicPropertyRegistry) {
			//val base = "http://localhost:${wireMock.port()}"
			reg.add("innsendingsapi.path") { "/innsendte/v1/files/[0-9a-fA-F-]{36}" }
			reg.add("joark.journal-post") { "/rest/journalpostapi/v1/journalpost" }
			reg.add("saf.path") { "/graphql" }
		}

	}

	@BeforeEach
	fun setup() {
		wireMock.resetAll()
		setupMockedNetworkServices(wireMock, portToExternalServices!!, journalPostUrl, "/innsendte/v1/files", safUrl)

		kafkaProducerForBadData = KafkaProducer(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})
		kafkaNologinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})
		kafkaLoggedinTopicProducer = KafkaProducer<String, String>(kafkaConfigMap().also {
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		})
	}

	@AfterEach
	fun teardown() {
		kafkaProducerForBadData.close()
		kafkaNologinTopicProducer.close()
		kafkaLoggedinTopicProducer.close()

		wireMock.resetRequests()
	}

	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		val initialRequests = countRequests(journalPostUrl, RequestMethod.POST)
		val soknadarkivschema = createSoknadarkivschema()
		mockSafRequest_notFound(innsendingsId = soknadarkivschema.innsendingsId)
		putDataOnKafkaTopic(soknadarkivschema)
		val soknadarkivschema2 = createSoknadarkivschema()
		mockSafRequest_notFound(innsendingsId = soknadarkivschema2.innsendingsId)
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyMockedPostRequests(initialRequests + 2, journalPostUrl)
	}

	@Test
	fun `Happy case - Putting noLoginevents on Kafka will cause rest calls to Joark`() {
		// Given
		mockFilestorageIsWorking(fileId)
		mockJoarkIsWorking()

		val initialRequests = countRequests(journalPostUrl, RequestMethod.POST)
		val soknadarkivschema = InnsendingTopicMsgBuilder()
			.withTittel("Test dokument")
			.withKanal("NAV_NO_UINNLOGGET")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", listOf(fileId)),
			))
			.build()
		val soknadarkivschema2 = InnsendingTopicMsgBuilder()
			.withTittel("Test dokument")
			.withKanal("NAV_NO_UINNLOGGET")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", listOf(fileId)),
			))
			.build()

		mockSafRequest_notFound(innsendingsId = soknadarkivschema.innsendingsId)
		mockSafRequest_notFound(innsendingsId = soknadarkivschema2.innsendingsId)

		// When
		putDataOnKafkaTopic(key = soknadarkivschema.innsendingsId, value = serializeMsg(soknadarkivschema), kanal ="NAV_NO_UINNLOGGET")
		putDataOnKafkaTopic(key = soknadarkivschema2.innsendingsId, value = serializeMsg(soknadarkivschema2), kanal ="NAV_NO_UINNLOGGET")

		// Expect
		verifyMockedPostRequests(initialRequests + 2, journalPostUrl)
	}

	@Test
	fun `Happy case - Putting loggedin events on Kafka will cause rest calls to Joark`() {
		// Given
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val initialRequests = countRequests(journalPostUrl, RequestMethod.POST)
		val soknadarkivschema = InnsendingTopicMsgBuilder()
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds)
			))
			.build()

		mockJoarkIsWorking()
		mockFilestorageIsWorking(fileIds.map { it to filestorageContent })
		mockSafRequest_notFound(innsendingsId = soknadarkivschema.innsendingsId)

		// When
		putDataOnKafkaTopic(key = soknadarkivschema.innsendingsId, value = soknadarkivschema)

		// Expect
		verifyMockedPostRequests(initialRequests + 1, journalPostUrl)
	}

	@Test
	fun `Happy case - Putting loggedIn and noLoginevents on Kafka will cause rest calls to Joark`() {
		// When
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		mockFilestorageIsWorking((fileIds + listOf(fileId)).map { it to filestorageContent })
		mockJoarkIsWorking()

		val initialRequests = countRequests(journalPostUrl, RequestMethod.POST)

		val loggedInMsg = createSoknadarkivschema()
		mockSafRequest_notFound(innsendingsId = loggedInMsg.innsendingsId)

		val loggedInMsg1 = InnsendingTopicMsgBuilder()
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds)
			))
			.build()

		mockSafRequest_notFound(innsendingsId = loggedInMsg1.innsendingsId)
		val loggedInMsg2 = InnsendingTopicMsgBuilder()
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds)
			))
			.build()
		mockSafRequest_notFound(innsendingsId = loggedInMsg2.innsendingsId)

		val noLogInMsg = InnsendingTopicMsgBuilder()
			.withKanal("NAV_NO_UINNLOGGET")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds)
			))
			.build()
		mockSafRequest_notFound(innsendingsId = noLogInMsg.innsendingsId)
		val noLogInMsg2 = InnsendingTopicMsgBuilder()
			.withKanal("NAV_NO_UINNLOGGET")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds)
			))
			.build()
		mockSafRequest_notFound(innsendingsId = noLogInMsg2.innsendingsId)

		// When
		putDataOnKafkaTopic(loggedInMsg)
		putDataOnKafkaTopic(key = loggedInMsg1.innsendingsId, value = loggedInMsg1)
		putDataOnKafkaTopic(key = loggedInMsg2.innsendingsId, value = loggedInMsg2)
		putDataOnKafkaTopic(key = noLogInMsg.innsendingsId, value = noLogInMsg)
		putDataOnKafkaTopic(key = noLogInMsg2.innsendingsId, value = noLogInMsg2)

		// Expect
		verifyMockedPostRequests(initialRequests + 5, journalPostUrl)
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
		mockSafRequest_notFound(innsendingsId = soknadarkivschema.innsendingsId)
		putDataOnKafkaTopic(soknadarkivschema)

		verifyMockedPostRequests(1, journalPostUrl)
	}

	@Test
	fun `Application not sent to Joark if it is already archived`() {
		val soknadarkivschema = InnsendingTopicMsgBuilder().withKanal("NAV_NO").build()
		val fileids = soknadarkivschema.dokumenter.map{it.varianter.map{it.uuid}}.flatten()

		mockFilestorageIsWorking(fileids.map {it to filestorageContent})
		mockSafRequest_found(innsendingsId = soknadarkivschema.innsendingsId)
		mockJoarkIsWorking()
		putDataOnKafkaTopic(soknadarkivschema)

		verifyMockedPostRequests(0, journalPostUrl)
	}

	@Test
	fun `Application sent to Joark if error checking SAF`() {
		val soknadarkivschema = InnsendingTopicMsgBuilder()
			.withInnsendtDato(
				OffsetDateTime.of(2026, 3, 1, 12, 30,30, 500, UTC
				))
			.withKanal("NAV_NO")
			.build()
		val fileids = soknadarkivschema.dokumenter.map{it.varianter.map{it.uuid}}.flatten()

		mockSafRequest_error(innsendingsId = soknadarkivschema.innsendingsId)
		mockFilestorageIsWorking(fileids.map {it to filestorageContent})
		mockJoarkIsWorking()
		putDataOnKafkaTopic(soknadarkivschema)

		verifyMockedPostRequests(1, journalPostUrl)
	}

	private fun createSoknadarkivschema() = createInnsendingTopicMsg(fileId)


	private fun putDataOnKafkaTopic(badData: String) {
		putDataOnKafkaTopic(UUID.randomUUID().toString(), badData, kanal="NAV_NO")
	}

	private fun putDataOnKafkaTopic(
		key: String,
		value: String,
		kanal: String = "NAV_NO",
	): RecordMetadata {
		return if (kanal == "NAV_NO") {
			putDataOnTopic(key, value, RecordHeaders(), topic = kafkaConfig.topics.loggedinSubmissionTopic, kafkaLoggedinTopicProducer)
		} else {
			putDataOnTopic(key, value, RecordHeaders(), topic = kafkaConfig.topics.nologinSubmissionTopic, kafkaNologinTopicProducer)
		}
	}

	private fun putDataOnKafkaTopic(
		key: String,
		value: InnsendingTopicMsg,
	): RecordMetadata {
		return if (value.kanal == "NAV_NO") {
			putDataOnTopic(key, serializeMsg(value), RecordHeaders(), topic = kafkaConfig.topics.loggedinSubmissionTopic, kafkaLoggedinTopicProducer)
		} else {
			putDataOnTopic(key, serializeMsg(value), RecordHeaders(), topic = kafkaConfig.topics.nologinSubmissionTopic, kafkaNologinTopicProducer)
		}
	}

	private fun putDataOnKafkaTopic(		value: InnsendingTopicMsg) : RecordMetadata {
		return putDataOnKafkaTopic(value.innsendingsId, value)
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
