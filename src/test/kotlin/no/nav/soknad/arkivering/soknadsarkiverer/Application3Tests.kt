package no.nav.soknad.arkivering.soknadsarkiverer

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

@Disabled
@ActiveProfiles("test")
@SpringBootTest
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class Application3Tests: TopologyTestDriverTests() {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Autowired
	private lateinit var appConfiguration: AppConfiguration

	@Autowired
	private lateinit var taskListService: TaskListService

	@Autowired
	private lateinit var metrics: ArchivingMetrics

	@MockBean
	private lateinit var kafkaPublisherMock: KafkaPublisher

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	private var maxNumberOfAttempts by Delegates.notNull<Int>()
	private val key = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		maxNumberOfAttempts = appConfiguration.config.retryTime.size
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(EventTypes.STARTED)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(
			EventTypes.STARTED
		)
		)}
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(EventTypes.ARCHIVED)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(
			EventTypes.ARCHIVED
		)
		)}
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(EventTypes.FINISHED)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(
			EventTypes.FINISHED
		)
		)}
		Mockito.`when`(kafkaPublisherMock.putProcessingEventOnTopic(any(), eq(ProcessingEvent(EventTypes.FAILURE)), any())).doAnswer {putDataOnProcessingTopic(key, ProcessingEvent(
			EventTypes.FAILURE
		)
		)}

		setupKafkaTopologyTestDriver()
			.withAppConfiguration(appConfiguration)
			.withTaskListService(taskListService)
			.withKafkaPublisher(kafkaPublisherMock)
			.putProcessingEventLogsOnTopic()
			.setup(metrics)
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
	fun `Failing to get files from Filestorage will cause retries`() {
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())
		TimeUnit.SECONDS.sleep(8)
		verifyProcessingEvents(1, EventTypes.STARTED)
		verifyProcessingEvents(0, EventTypes.ARCHIVED)
		verifyProcessingEvents(0, EventTypes.FINISHED)
		verifyProcessingEvents(1, EventTypes.FAILURE)
		verifyDeleteRequestsToFilestorage(0)
		verifyMessageStartsWith(maxNumberOfAttempts, "Exception")
		verifyMessageStartsWith(0, "ok")
		verifyMetric(0, "get files from filestorage")
		verifyMetric(0, "send files to archive")
		verifyMetric(0, "delete files from filestorage")

		assertEquals(getFilestorageErrorsBefore + maxNumberOfAttempts, metrics.getGetFilestorageErrors())
		assertEquals(getFilestorageSuccessesBefore + 0, metrics.getGetFilestorageSuccesses())
		assertEquals(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		assertEquals(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		assertEquals(joarkSuccessesBefore + 0, metrics.getJoarkSuccesses())
		assertEquals(tasksBefore + 1, metrics.getTasks())
		assertEquals(tasksGivenUpOnBefore + 1, metrics.getTasksGivenUpOn())
	}


	@Test
	fun `Files deleted from Filestorage will cause finishing archiving`() {
		val tasksBefore = metrics.getTasks()
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val getFilestorageErrorsBefore = metrics.getGetFilestorageErrors()
		val getFilestorageSuccessesBefore = metrics.getGetFilestorageSuccesses()
		val delFilestorageSuccessesBefore = metrics.getDelFilestorageSuccesses()
		val joarkSuccessesBefore = metrics.getJoarkSuccesses()
		val joarkErrorsBefore = metrics.getJoarkErrors()

		mockRequestedFileIsGone()
		mockJoarkIsWorking()

		putDataOnKafkaTopic(createSoknadarkivschema())
		TimeUnit.SECONDS.sleep(8)
		verifyProcessingEvents(1, EventTypes.STARTED)
		verifyProcessingEvents(1, EventTypes.ARCHIVED)
		verifyProcessingEvents(1, EventTypes.FINISHED)
		verifyProcessingEvents(0, EventTypes.FAILURE)
		verifyDeleteRequestsToFilestorage(1)
		verifyMessageStartsWith(1, "ok")
		verifyMetric(0, "get files from filestorage")
		verifyMetric(0, "send files to archive")
		verifyMetric(1, "delete files from filestorage")

		assertEquals(getFilestorageErrorsBefore + 1, metrics.getGetFilestorageErrors())
		assertEquals(getFilestorageSuccessesBefore + 0, metrics.getGetFilestorageSuccesses())
		assertEquals(delFilestorageSuccessesBefore + 0, metrics.getDelFilestorageSuccesses())
		assertEquals(joarkErrorsBefore + 0, metrics.getJoarkErrors())
		assertEquals(joarkSuccessesBefore + 0, metrics.getJoarkSuccesses())
		assertEquals(tasksBefore, metrics.getTasks())
		assertEquals(tasksGivenUpOnBefore, metrics.getTasksGivenUpOn())
	}

	private fun verifyMessageStartsWith(expectedCount: Int, message: String, key: String = this.key) {
		verifyMessageStartsWithSupport(kafkaPublisherMock, expectedCount, message, key)
	}

	private fun verifyMetric(expectedCount: Int, metric: String, key: String = this.key) {
		verifyMetricSupport(kafkaPublisherMock, expectedCount, metric, key)
	}

	private fun verifyProcessingEvents(expectedCount: Int, eventType: EventTypes) {
		verifyProcessingEventsSupport(kafkaPublisherMock, expectedCount, eventType, key)
	}

	private fun putDataOnKafkaTopic(data: Soknadarkivschema) {
		putDataOnInputTopic(key, data)
	}

	private fun verifyDeleteRequestsToFilestorage(expectedCount: Int) {
		verifyMockedDeleteRequests(expectedCount, appConfiguration.config.filestorageUrl.replace("?", "\\?") + ".*")
	}
}
