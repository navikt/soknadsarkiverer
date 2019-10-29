package no.nav.soknad.arkivering.soknadsarkiverer

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaHost
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaPort
import no.nav.soknad.arkivering.soknadsarkiverer.IntegrationTests.Companion.kafkaTopic
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@EnableKafka
@EmbeddedKafka(topics = [kafkaTopic], brokerProperties = ["listeners=PLAINTEXT://$kafkaHost:$kafkaPort", "port=$kafkaPort"])
class IntegrationTests {

	@Autowired
	private lateinit var applicationProperties: ApplicationProperties
	private val wiremockServer = WireMockServer(joarkPort)
	private val kafkaTemplate = kafkaTemplate()

	@BeforeEach
	fun setup() {
		wiremockServer.start()
	}

	@AfterEach
	fun teardown() {
		wiremockServer.stop()
	}


	@Test
	fun `Putting messages on Kafka will cause a rest call to Joark`() {
		mockJoarkIsWorking()
		val archivalData = ArchivalData("id", "message")

		kafkaTemplate.send(kafkaTopic, "key", archivalData)

		verifyWiremockRequests(1, applicationProperties.joarkUrl, RequestMethod.POST)
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
		wiremockServer.verify(1, postRequestedFor(urlMatching(applicationProperties.joarkUrl)))
	}

	private fun mockJoarkIsWorking() {
		mockJoark(200)
	}

	private fun mockJoark(statusCode: Int) {
		wiremockServer.stubFor(
			post(urlEqualTo(applicationProperties.joarkUrl))
				.willReturn(aResponse().withStatus(statusCode)))
	}


	private fun producerFactory(): ProducerFactory<String, ArchivalData> {
		val configProps = HashMap<String, Any>().also {
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = "$kafkaHost:$kafkaPort"
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JsonSerializer::class.java
		}
		return DefaultKafkaProducerFactory(configProps)
	}

	private final fun kafkaTemplate() = KafkaTemplate(producerFactory())

	companion object {
		const val kafkaTopic = "privat-soknadInnsendt-sendsoknad-v1-q0"
		const val kafkaHost = "localhost"
		const val kafkaPort = 3333
		const val joarkPort = 2908
	}
}
