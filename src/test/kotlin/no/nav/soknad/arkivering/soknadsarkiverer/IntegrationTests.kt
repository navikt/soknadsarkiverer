package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaHost
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaPort
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaTopic
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.annotation.EnableKafka
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
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@EnableKafka
@EmbeddedKafka(topics = [kafkaTopic], brokerProperties = ["listeners=PLAINTEXT://$kafkaHost:$kafkaPort", "port=$kafkaPort"])
class IntegrationTests {

	@Autowired
	private lateinit var applicationProperties: ApplicationProperties
	private val dlqKafkaBroker = EmbeddedKafkaBroker(1, true, kafkaDlqTopic)
	private val objectMapper = ObjectMapper()
	private val wiremockServer = WireMockServer(joarkPort)
	private val kafkaTemplate = kafkaTemplate()
	private val consumedDlqRecords = LinkedBlockingQueue<ConsumerRecord<ByteArray, ByteArray>>()
	private lateinit var container: KafkaMessageListenerContainer<ByteArray, ByteArray>

	@BeforeEach
	fun setup() {
		wiremockServer.start()
		setupDlqListener()
	}

	@AfterEach
	fun teardown() {
		wiremockServer.stop()
	}


	@Test
	fun `Putting messages on Kafka will cause a rest call to Joark`() {
		mockJoarkIsWorking()
		val archivalData = ArchivalData("id", "message")

		putDataOnKafkaTopic(archivalData)

		verifyWiremockRequests(1, applicationProperties.joarkUrl, RequestMethod.POST)
	}

	@Test
	fun `Sending in invalid json will fail`() {
		val invalidData = "this is not json"

		putDataOnKafkaTopic(invalidData)

		val receivedRecord = consumedDlqRecords.poll(30, TimeUnit.SECONDS)
		assertNotNull(receivedRecord)
	}

	@Test
	fun `Failing to send to Joark will put event on retry topic`() {
		mockJoarkIsDown()
		val archivalData = ArchivalData("id2", "message")

		putDataOnKafkaTopic(archivalData)

		//TODO: Test that there is a message on retry topic
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
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = "$kafkaHost:$kafkaPort"
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		}
		return DefaultKafkaProducerFactory(configProps)
	}

	private final fun kafkaTemplate() = KafkaTemplate(producerFactory())


	private fun setupDlqListener() {
		dlqKafkaBroker.kafkaPorts(kafkaPort)
		dlqKafkaBroker.brokerListProperty("listeners=PLAINTEXT://$kafkaHost:$kafkaPort")
		val consumerProperties = KafkaTestUtils.consumerProps("sender", "false", dlqKafkaBroker).also {
			it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = "${kafkaHost}:${kafkaPort}"
			it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java
			it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java
			it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = LogAndContinueExceptionHandler::class.java
		}

		val consumer = DefaultKafkaConsumerFactory<ByteArray, ByteArray>(consumerProperties)

		val msgListener = MessageListener<ByteArray, ByteArray> { record ->
			consumedDlqRecords.add(record)
		}

		val containerProperties = ContainerProperties(kafkaDlqTopic)
		containerProperties.isMissingTopicsFatal = false
		container = KafkaMessageListenerContainer(consumer, containerProperties)
		container.setupMessageListener(msgListener)
		container.start()

		ContainerTestUtils.waitForAssignment(container, dlqKafkaBroker.partitionsPerTopic)
	}

	companion object {
		const val kafkaTopic = "privat-soknadInnsendt-sendsoknad-v1-q0"
		const val kafkaDlqTopic = "dlq"
		const val kafkaHost = "localhost"
		const val kafkaPort = 3333
		const val joarkPort = 2908
	}
}
