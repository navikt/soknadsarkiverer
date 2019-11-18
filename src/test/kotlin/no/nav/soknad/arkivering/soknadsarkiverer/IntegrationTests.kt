package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaDlqTopic
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaRetryTropic
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaTopic
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@EmbeddedKafka(topics = [kafkaTopic, kafkaDlqTopic, kafkaRetryTropic])
class IntegrationTests {

	@Autowired
	private lateinit var applicationProperties: ApplicationProperties
	@Autowired
	private lateinit var applicationContext: ApplicationContext

	private lateinit var kafkaBroker: EmbeddedKafkaBroker
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>
	private val objectMapper = ObjectMapper()
	private val wiremockServer = WireMockServer(joarkPort)
	private val consumedDlqRecords = LinkedBlockingQueue<ConsumerRecord<ByteArray, ByteArray>>()
	private val consumedRetryRecords = LinkedBlockingQueue<ConsumerRecord<ByteArray, ByteArray>>()

	@BeforeEach
	fun setup() {
		wiremockServer.start()

		kafkaBroker = applicationContext.getBean()
		kafkaTemplate = kafkaTemplate()

		setupDlqListener()
		setupRetryListener()
	}

	@AfterEach
	fun teardown() {
		wiremockServer.stop()
	}


	@Test
	@DirtiesContext
	fun `Putting messages on Kafka will cause a rest call to Joark`() {
		mockJoarkIsWorking()
		val archivalData = ArchivalData("id", "message")

		putDataOnKafkaTopic(archivalData)

		verifyWiremockRequests(1, applicationProperties.joarkUrl, RequestMethod.POST)
	}

	@Test
	@DirtiesContext
	fun `Sending in invalid json will produce event on DLQ`() {
		val invalidData = "this is not json"

		putDataOnKafkaTopic(invalidData)

		val receivedRecord = consumedDlqRecords.poll(30, TimeUnit.SECONDS)
		assertNotNull(receivedRecord)
	}

	@Test
	@DirtiesContext
	fun `Failing to send to Joark will put event on retry topic`() {
		mockJoarkIsDown()
		val archivalData = ArchivalData("id2", "message")

		putDataOnKafkaTopic(archivalData)

		val receivedRecord = consumedRetryRecords.poll(30, TimeUnit.SECONDS)
		assertNotNull(receivedRecord)
	}



	private fun putDataOnKafkaTopic(archivalData: ArchivalData) {
		putDataOnKafkaTopic(objectMapper.writeValueAsString(archivalData))
	}

	private fun putDataOnKafkaTopic(data: String) {
		kafkaTemplate.send(kafkaTopic, "key", data)
	}

	private fun verifyWiremockRequests(expectedCount: Int, url: String, requestMethod: RequestMethod) {
		val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, urlEqualTo(url)).build()
		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val matches = wiremockServer.countRequestsMatching(requestPattern)

			if (matches.count == expectedCount) {
				break
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		wiremockServer.verify(expectedCount, postRequestedFor(urlMatching(applicationProperties.joarkUrl)))
	}

	private fun mockJoarkIsWorking() {
		mockJoark(200)
	}

	private fun mockJoarkIsDown() {
		mockJoark(404)
	}

	private fun mockJoark(statusCode: Int) {
		wiremockServer.stubFor(
			post(urlEqualTo(applicationProperties.joarkUrl))
				.willReturn(aResponse().withStatus(statusCode)))
	}

	private fun producerFactory(): ProducerFactory<String, String> {
		val configProps = HashMap<String, Any>().also {
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaBroker.brokersAsString
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		}
		return DefaultKafkaProducerFactory(configProps)
	}

	private final fun kafkaTemplate() = KafkaTemplate(producerFactory())


	private fun setupDlqListener() {
		val kafkaDlqTopic = applicationProperties.kafkaDeadLetterTopic
		val msgListener = MessageListener<ByteArray, ByteArray> { record -> consumedDlqRecords.add(record) }

		setupKafkaListener(kafkaDlqTopic, msgListener)
	}

	private fun setupRetryListener() {
		val kafkaDlqTopic = applicationProperties.kafkaRetryTopic
		val msgListener = MessageListener<ByteArray, ByteArray> { record -> consumedRetryRecords.add(record) }

		setupKafkaListener(kafkaDlqTopic, msgListener)
	}

	private fun setupKafkaListener(topic: String, msgListener: MessageListener<ByteArray, ByteArray>) {
		val consumerProperties = KafkaTestUtils.consumerProps("sender", "false", kafkaBroker)

		val consumer = DefaultKafkaConsumerFactory<ByteArray, ByteArray>(consumerProperties)
		consumer.setKeyDeserializer(ByteArrayDeserializer())
		consumer.setValueDeserializer(ByteArrayDeserializer())

		val containerProperties = ContainerProperties(topic)
		containerProperties.isMissingTopicsFatal = false
		val container = KafkaMessageListenerContainer(consumer, containerProperties)
		container.setupMessageListener(msgListener)
		container.start()

		ContainerTestUtils.waitForAssignment(container, kafkaBroker.partitionsPerTopic)
	}

	companion object {
		const val kafkaTopic = "privat-soknadInnsendt-sendsoknad-v1-q0"
		const val kafkaRetryTropic = "privat-retry-soknadInnsendt-sendsoknad-v1-q0"
		const val kafkaDlqTopic = "privat-dlq-soknadInnsendt-sendsoknad-v1-q0"
		const val joarkPort = 2908
	}
}
