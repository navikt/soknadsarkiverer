package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.dto.ArchivalData
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.set

@ActiveProfiles("test")
@SpringBootTest
@EmbeddedKafka(topics = ["\${application.kafka-topic}", "\${application.kafka-retry-topic}", "\${application.kafka-dead-letter-topic}"])
class IntegrationTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	private val timeout = 30L

	@Autowired
	private lateinit var applicationProperties: ApplicationProperties
	@Autowired
	private lateinit var applicationContext: ApplicationContext

	private lateinit var kafkaBroker: EmbeddedKafkaBroker
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>
	private val objectMapper = ObjectMapper()
	private val consumedMainRecords = LinkedBlockingQueue<ConsumerRecord<ByteArray, ByteArray>>()
	private val consumedDlqRecords = LinkedBlockingQueue<ConsumerRecord<ByteArray, ByteArray>>()
	private val consumedRetryRecords = LinkedBlockingQueue<ConsumerRecord<ByteArray, ByteArray>>()

	@BeforeEach
	fun setup() {
		setupMockedServices(portToExternalServices!!, applicationProperties.joarkUrl, applicationProperties.filestorageUrl)

		kafkaBroker = applicationContext.getBean()
		kafkaTemplate = kafkaTemplate()

		setupMainListener()
		setupDlqListener()
		setupRetryListener()
	}

	@AfterEach
	fun teardown() {
		stopMockedServices()
	}


	@Test
	@DirtiesContext
	fun `Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(ArchivalData("id0", "message0"))
		putDataOnKafkaTopic(ArchivalData("id1", "message1"))

		verifyMockedPostRequests(2, applicationProperties.joarkUrl)
	}

	@Test
	@DirtiesContext
	fun `Sending in invalid json will produce event on DLQ`() {
		val invalidData = "this is not json"

		putDataOnKafkaTopic(invalidData)

		assertNotNull(consumedDlqRecords.poll(timeout, TimeUnit.SECONDS))
	}

	@Test
	@DirtiesContext
	fun `Failing to send to Joark will put event on retry topic`() {
		mockFilestorageIsWorking()
		mockJoarkIsDown()

		putDataOnKafkaTopic(ArchivalData("id", "message"))

		assertNotNull(consumedRetryRecords.poll(timeout, TimeUnit.SECONDS))
	}

	@Test
	@DirtiesContext
	fun `Failing to get files from Filestorage will put event on retry topic`() {
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(ArchivalData("id", "message"))

		assertNotNull(consumedRetryRecords.poll(timeout, TimeUnit.SECONDS))
	}

	@Test
	@DirtiesContext
	fun `Poison pill followed by proper event -- One event on DLQ, one to Joark`() {
		mockFilestorageIsWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic("this is not json")
		putDataOnKafkaTopic(ArchivalData("id", "message"))

		assertNotNull(consumedDlqRecords.poll(timeout, TimeUnit.SECONDS))
		verifyMockedPostRequests(1, applicationProperties.joarkUrl)
	}

	@Test
	@DirtiesContext
	fun `First attempt to Joark fails, the second succeeds`() {
		mockFilestorageIsWorking()
		mockJoarkRespondsAfterAttempts(1)

		putDataOnKafkaTopic(ArchivalData("id", "message"))

		assertNotNull(consumedRetryRecords.poll(timeout, TimeUnit.SECONDS))
		verifyMockedPostRequests(2, applicationProperties.joarkUrl)
	}

	@Test
	@DirtiesContext
	fun `First attempt to Joark fails, the fourth succeeds`() {
		mockFilestorageIsWorking()
		mockJoarkRespondsAfterAttempts(3)

		putDataOnKafkaTopic(ArchivalData("id", "message"))

		assertNotNull(consumedRetryRecords.poll(timeout, TimeUnit.SECONDS))
		verifyMockedPostRequests(4, applicationProperties.joarkUrl)
	}

	@Test
	@DirtiesContext
	fun `Joark is down -- message ends up on DLQ`() {
		mockFilestorageIsWorking()
		mockJoarkIsDown()

		putDataOnKafkaTopic(ArchivalData("id", "message"))

		assertNotNull(consumedRetryRecords.poll(timeout, TimeUnit.SECONDS))
		assertNotNull(consumedDlqRecords.poll(timeout, TimeUnit.SECONDS))
		verifyMockedPostRequests(applicationProperties.kafkaMaxRetryCount!!, applicationProperties.joarkUrl)
	}

	@Test
	@DirtiesContext
	fun `Put event on retry topic, then send another event on main topic -- one topic should not lock the other`() {

		// This test uses semaphores to guard in which order things happen.
		// First an event is produced to the main topic, but the Filestorage is set to fail the event so that it
		// ends up on the retry topic. When it is read and the application tries to access the Filestorage a second
		// time, two things will happen:
		// * A semaphore is released so that a second event is sent to the main topic.
		// * It will wait for a different semaphore to release, and will be stuck until then.
		// When the second event to the main topic is read, the Filestorage will work, and once it reaches Joark,
		// it will release the semaphore that the first event is waiting for. That event will then continue executing
		// and end up in Joark.
		//
		// The purpose of this test is to make sure that events from both the main and retry topics can be read
		// simultaneously. We do not wish for the application to be stuck processing only one topic at the time.

		val continueProcessingFirstMessageLock = Semaphore(0)
		val sendSecondMessageLock = Semaphore(0)
		val lockingService = MockLockingServices(continueProcessingFirstMessageLock, sendSecondMessageLock)

		stopMockedServices()
		setupMockedServices(portToExternalServices!!, applicationProperties.joarkUrl, applicationProperties.filestorageUrl,
			lockingService::giveFilestorageResponse, lockingService::giveJoarkResponse)

		putDataOnKafkaTopic(ArchivalData("id0", "first"))
		sendSecondMessageLock.acquire() // Will only proceed from here once the lock is released
		putDataOnKafkaTopic(ArchivalData("id1", "second"))

		assertNotNull(consumedRetryRecords.poll(timeout, TimeUnit.SECONDS))
		assertNotNull(consumedMainRecords.poll(timeout, TimeUnit.SECONDS))
		verifyMockedPostRequests(2, applicationProperties.joarkUrl)
		verifyMockedGetRequests(3, applicationProperties.filestorageUrl.replace("?", "\\?") + ".*")
	}

	class MockLockingServices(private val continueProcessingFirstMessageLock: Semaphore, private val sendSecondMessageLock: Semaphore) {
		private var filestorageRequestCount = 0

		fun giveFilestorageResponse(): ResponseMocker {
			filestorageRequestCount++

			if (filestorageRequestCount == 1) {
				return ResponseMocker().withStatus(HttpStatus.NOT_FOUND)

			} else if (filestorageRequestCount == 2) {
				sendSecondMessageLock.release()
				continueProcessingFirstMessageLock.acquire()
			}

			return ResponseMocker()
				.withStatus(HttpStatus.OK)
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createFilestorageResponse())
		}

		fun giveJoarkResponse(): ResponseMocker {
			continueProcessingFirstMessageLock.release()
			return ResponseMocker().withStatus(HttpStatus.OK)
		}
	}


	private fun putDataOnKafkaTopic(archivalData: ArchivalData) {
		putDataOnKafkaTopic(objectMapper.writeValueAsString(archivalData))
	}

	private fun putDataOnKafkaTopic(data: String) {
		kafkaTemplate.send(applicationProperties.kafkaTopic, "key", data)
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


	private fun setupMainListener() {
		val topic = applicationProperties.kafkaTopic
		val msgListener = MessageListener<ByteArray, ByteArray> { record -> consumedMainRecords.add(record) }

		setupKafkaListener(topic, msgListener)
	}

	private fun setupDlqListener() {
		val topic = applicationProperties.kafkaDeadLetterTopic
		val msgListener = MessageListener<ByteArray, ByteArray> { record -> consumedDlqRecords.add(record) }

		setupKafkaListener(topic, msgListener)
	}

	private fun setupRetryListener() {
		val topic = applicationProperties.kafkaRetryTopic
		val msgListener = MessageListener<ByteArray, ByteArray> { record -> consumedRetryRecords.add(record) }

		setupKafkaListener(topic, msgListener)
	}

	private fun setupKafkaListener(topic: String, msgListener: MessageListener<ByteArray, ByteArray>) {
		val consumerProperties = KafkaTestUtils.consumerProps("integration-test-listener", "false", kafkaBroker)

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
}
