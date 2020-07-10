package no.nav.soknad.arkivering.soknadsarkiverer

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.startsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

@ActiveProfiles("test")
@SpringBootTest
class ApplicationTests : TopologyTestDriverTests() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Autowired
	private lateinit var appConfiguration: AppConfiguration

	@Autowired
	private lateinit var taskListService: TaskListService

	@MockBean
	private lateinit var kafkaPublisherMock: KafkaPublisher

	private var maxNumberOfAttempts by Delegates.notNull<Int>()

	private val uuid = UUID.randomUUID().toString()
	private val key = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		maxNumberOfAttempts = appConfiguration.config.retryTime.size

		setupKafkaTopologyTestDriver(appConfiguration, taskListService, kafkaPublisherMock)

		mockKafkaPublisher(RECEIVED)
		mockKafkaPublisher(STARTED)
		mockKafkaPublisher(FINISHED)
	}

	fun mockKafkaPublisher(eventType: EventTypes) {
		whenever(kafkaPublisherMock.putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(eventType)), any()))
			.then { processingEventTopic.pipeInput(key, ProcessingEvent(eventType)) }
	}

	@AfterEach
	fun teardown() {
		stopMockedNetworkServices()
		closeTestDriver()
		MockSchemaRegistry.dropScope(schemaRegistryScope)

		reset(kafkaPublisherMock)
		clearInvocations(kafkaPublisherMock)
	}


	@Test
	fun `Happy case - Putting events on Kafka will cause rest calls to Joark`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, RECEIVED)
		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(0, "Exception")
	}

	@Test
	fun `Sending in invalid data will not create Processing Events`() {
		val invalidData = "this string is not deserializable"

		putDataOnKafkaTopic(key, invalidData)

		verifyMessageStartsWith(1, "Exception")
		TimeUnit.MILLISECONDS.sleep(500)
		verifyProcessingEvents(0, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyMockedPostRequests(0, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
	}

	@Test
	fun `Failing to send to Joark will cause retries`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsDown()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(maxNumberOfAttempts, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
	}

	@Test
	fun `Failing to get files from Filestorage will cause retries`() {
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(maxNumberOfAttempts, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
	}

	@Test
	fun `Poison pill followed by proper event -- Only proper one is sent to Joark`() {
		val keyForPoisionPill = UUID.randomUUID().toString()
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorking()

		putDataOnKafkaTopic(keyForPoisionPill, "this is not deserializable")
		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception", keyForPoisionPill)
		verifyMessageStartsWith(1, "ok", key)
	}

	@Test
	fun `First attempt to Joark fails, the second succeeds`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkRespondsAfterAttempts(1)

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(2, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(2, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "Exception")
		verifyMessageStartsWith(1, "ok")
	}

	@Test
	fun `First attempt to Joark fails, the fourth succeeds`() {
		val attemptsToFail = 3
		mockFilestorageIsWorking(uuid)
		mockJoarkRespondsAfterAttempts(attemptsToFail)

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(attemptsToFail + 1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(attemptsToFail + 1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(attemptsToFail, "Exception")
	}

	@Test
	fun `Everything works, but Filestorage cannot delete files -- Message is nevertheless marked as finished`() {
		mockFilestorageIsWorking(uuid)
		mockFilestorageDeletionIsNotWorking()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(1, STARTED)
		verifyProcessingEvents(1, ARCHIVED)
		verifyProcessingEvents(1, FINISHED)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMessageStartsWith(0, "Exception")
	}

	@Test
	fun `Joark responds with status OK but invalid body -- will retry`() {
		mockFilestorageIsWorking(uuid)
		mockJoarkIsWorkingButGivesInvalidResponse()

		putDataOnKafkaTopic(createSoknadarkivschema())

		verifyProcessingEvents(maxNumberOfAttempts, STARTED)
		verifyProcessingEvents(0, ARCHIVED)
		verifyProcessingEvents(0, FINISHED)
		verifyMockedPostRequests(maxNumberOfAttempts, appConfiguration.config.joarkUrl)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
	}


	private fun verifyMessageStartsWith(expectedCount: Int, message: String, key: String = this.key) {
		val getCount = {
			mockingDetails(kafkaPublisherMock)
				.invocations.stream()
				.filter { it.arguments[0] == key }
				.filter { it.arguments[1] is String }
				.filter { (it.arguments[1] as String).startsWith(message) }
				.count()
				.toInt()
		}

		val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putMessageOnTopic(eq(key), startsWith(message), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun verifyProcessingEvents(expectedCount: Int, eventType: EventTypes) {
		val type = ProcessingEvent(eventType)
		val getCount = {
			mockingDetails(kafkaPublisherMock)
				.invocations.stream()
				.filter { it.arguments[0] == key }
				.filter { it.arguments[1] == type }
				.count()
				.toInt()
		}

		val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putProcessingEventOnTopic(eq(key), eq(type), any()) }
		loopAndVerify(expectedCount, getCount, finalCheck)
	}

	private fun putDataOnKafkaTopic(data: Soknadarkivschema) {
		inputTopic.pipeInput(key, data)
	}

	private fun putDataOnKafkaTopic(key: String, data: String) {
		inputTopicForBadData.pipeInput(key, data)
	}

	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, appConfiguration.config.filestorageUrl.replace("?", "\\?") + ".*")
	}

	private fun createSoknadarkivschema() = createSoknadarkivschema(uuid)
}
