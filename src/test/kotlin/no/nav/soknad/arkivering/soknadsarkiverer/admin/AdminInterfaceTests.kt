package no.nav.soknad.arkivering.soknadsarkiverer.admin

import com.nhaarman.mockitokotlin2.*
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.STARTED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.config.Scheduler
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.ArchiverService
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.server.ResponseStatusException
import java.util.*
import java.util.concurrent.TimeUnit

@ActiveProfiles("test")
@SpringBootTest
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
class AdminInterfaceTests : TopologyTestDriverTests()  {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@MockBean
	private lateinit var kafkaPublisherMock: KafkaPublisher

	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Autowired
	private lateinit var archiverService: ArchiverService

	@Autowired
	private lateinit var fileservice: FileserviceInterface

	private val appConfiguration = createAppConfiguration()
	private val scheduler = mock<Scheduler>()
	private lateinit var taskListService: TaskListService
	private lateinit var adminInterface: AdminInterface

	private val maxNumberOfAttempts = appConfiguration.config.retryTime.size

	@BeforeEach
	fun setup() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)

		taskListService = TaskListService(archiverService, appConfiguration, scheduler)
		adminInterface = AdminInterface(taskListService, fileservice)

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
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		putDataOnInputTopic(key, soknadarkivschema)
		verifyProcessingEvents(key, maxNumberOfAttempts, STARTED)
		loopAndVerify(maxNumberOfAttempts + 1, { getTaskListCount(key) })

		mockFilestorageIsWorking(fileUuid)
		adminInterface.rerun(key)

		loopAndVerify(0, { taskListService.listTasks().size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
	}

	@Test
	fun `Rerunning finished task wont cause hanging`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnInputTopic(key, soknadarkivschema)
		loopAndVerify(0, { taskListService.listTasks().size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)

		adminInterface.rerun(key)

		TimeUnit.SECONDS.sleep(1)
		loopAndVerify(0, { taskListService.listTasks().size })
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)
	}

	@Test
	fun `Rerunning blocked task wont block caller`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking(10_000)

		putDataOnInputTopic(key, soknadarkivschema)

		val timeBeforeRerun = System.currentTimeMillis()
		adminInterface.rerun(key)

		loopAndVerify(1, { taskListService.listTasks().size })
		assertTrue(System.currentTimeMillis() - timeBeforeRerun < 100, "This operation should not take a long time to perform")
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
	fun `Querying file that does not exist throws 404`() {
		val key = UUID.randomUUID().toString()
		val fileUuid = UUID.randomUUID().toString()
		val soknadarkivschema = createSoknadarkivschema(fileUuid)
		mockFilestorageIsWorking(fileUuid)
		mockJoarkIsWorking()

		putDataOnInputTopic(key, soknadarkivschema)
		verifyMockedPostRequests(1, appConfiguration.config.joarkUrl)


		assertThrows<ResponseStatusException> {
			adminInterface.filesExists(UUID.randomUUID().toString())
		}
	}

	@Test
	fun `Can query if file exists`() {
		mockFilestorageIsDown()
		mockJoarkIsWorking()

		val key = UUID.randomUUID().toString()
		val fileUuidInFilestorage = UUID.randomUUID().toString()
		val fileUuidNotInFilestorage = UUID.randomUUID().toString()

		val soknadarkivschema = createSoknadarkivschema(listOf(fileUuidInFilestorage, fileUuidNotInFilestorage))

		putDataOnInputTopic(key, soknadarkivschema)
		verifyProcessingEvents(key, maxNumberOfAttempts, STARTED)


		mockFilestorageIsWorking(listOf(fileUuidInFilestorage to "filecontent", fileUuidNotInFilestorage to null))

		val response = adminInterface.filesExists(key)

		assertEquals(2, response.size)
		assertEquals(fileUuidInFilestorage, response[0].id)
		assertEquals("Exists", response[0].status)
		assertEquals(fileUuidNotInFilestorage, response[1].id)
		assertEquals("Does not exist", response[1].status)
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
