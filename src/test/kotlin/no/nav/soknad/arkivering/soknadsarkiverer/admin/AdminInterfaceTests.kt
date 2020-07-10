package no.nav.soknad.arkivering.soknadsarkiverer.admin

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.STARTED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.*

@ActiveProfiles("test")
@SpringBootTest
class AdminInterfaceTests : TopologyTestDriverTests()  {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Autowired
	private lateinit var adminInterface: AdminInterface

	@MockBean
	private lateinit var kafkaPublisherMock: KafkaPublisher

	private val appConfiguration = createAppConfiguration()
	private val archiverService = mock<ArchiverService>()
	private val scheduler = mock<Scheduler>()
	private val taskListService = TaskListService(archiverService, appConfiguration, scheduler)

	private val maxNumberOfAttempts = appConfiguration.config.retryTime.size

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		setupKafkaTopologyTestDriver()
			.withAppConfiguration(appConfiguration)
			.withTaskListService(taskListService)
			.withKafkaPublisher(kafkaPublisherMock)
			.runScheduledTasksOnScheduling(scheduler)
			.putProcessingEventLogsOnTopic()
			.setup()
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
	fun `Can call rerun`() {
		val key = UUID.randomUUID().toString()
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnInputTopic(key, createSoknadarkivschema())
		verifyProcessingEvents(key, maxNumberOfAttempts, STARTED)
		assertEquals(maxNumberOfAttempts, getTaskListCount(key))

		adminInterface.rerun(key)
		//TODO: Make proper test
	}

	@Test
	fun `Can request allEvents`() {
		adminInterface.allEvents()
		//TODO: Make proper test
	}

	@Test
	fun `Can request unfinishedEvents`() {
		adminInterface.unfinishedEvents()
		//TODO: Make proper test
	}

	@Test
	fun `Can request specific event`() {
		adminInterface.specificEvent(UUID.randomUUID().toString())
		//TODO: Make proper test
	}

	@Test
	fun `Can request eventContent`() {
		adminInterface.eventContent(UUID.randomUUID().toString())
		//TODO: Make proper test
	}

	@Test
	fun `Can query if file exists`() {
		adminInterface.filesExists(UUID.randomUUID().toString())
		//TODO: Make proper test
	}

	private fun verifyProcessingEvents(key: String, expectedCount: Int, eventType: EventTypes) { // TODO: Duplicated method
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

	private fun getTaskListCount(key: String) = getTaskListPair(key).first
	private fun getTaskListPair (key: String) = taskListService.listTasks()[key] ?: error("Expected to find $key in map")
}
