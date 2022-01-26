package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import io.prometheus.client.CollectorRegistry
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.util.*

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
@EnableConfigurationProperties(ClientConfigurationProperties::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilestorageServiceTests {

	@Value("\${application.mocked-port-for-external-services}")
	private val portToExternalServices: Int? = null

	@Suppress("unused")
	@MockBean
	private lateinit var clientConfigurationProperties: ClientConfigurationProperties

	@Suppress("unused")
	@MockBean
	private lateinit var collectorRegistry: CollectorRegistry

	@Autowired
	private lateinit var appConfiguration: AppConfiguration

	@Autowired
	private lateinit var filestorageService: FilestorageService

	private lateinit var fileIdsAndResponses: List<Pair<String, String>>
	private val key = UUID.randomUUID().toString()

	@BeforeAll
	fun beforeAll() {
		setupMockedNetworkServices(portToExternalServices!!, appConfiguration.config.joarkUrl, appConfiguration.config.filestorageUrl)
	}

	@BeforeEach
	fun setup() {
		fileIdsAndResponses = (0 until 100).map { number -> UUID.randomUUID().toString() to number.toString() }
	}

	@AfterAll
	fun teardown() {
		stopMockedNetworkServices()
	}


	@Test
	fun `getFilesFromFilestorage - Asking for 0 files - Makes one request - Empty list is returned`() {
		val numberOfFiles = 0

		val files = mockNumberOfFilesAndPerformRequest(numberOfFiles)

		assertEquals(numberOfFiles, files.size)
		assertFileContentIsCorrect(files)
		verifyMockedGetRequests(0, makeUrl(fileIdsAndResponses.take(numberOfFiles)))
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 1 file - Makes one request - List of 1 is returned`() {
		val numberOfFiles = 1

		val files = mockNumberOfFilesAndPerformRequest(numberOfFiles)

		assertEquals(numberOfFiles, files.size)
		assertFileContentIsCorrect(files)
		verifyMockedGetRequests(1, makeUrl(fileIdsAndResponses.take(numberOfFiles)))
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 6 files - Makes two requests - List of 6 is returned`() {
		val numberOfFiles = 6
		mockFilestorageIsWorking(fileIdsAndResponses.take(filesInOneRequestToFilestorage))
		mockFilestorageIsWorking(fileIdsAndResponses.drop(filesInOneRequestToFilestorage).take(1))
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		val files = filestorageService.getFilesFromFilestorage(key, soknadarkivschema)

		assertEquals(numberOfFiles, files.size)
		assertFileContentIsCorrect(files)
		verifyMockedGetRequests(1, makeUrl(fileIdsAndResponses.take(filesInOneRequestToFilestorage)))
		verifyMockedGetRequests(1, makeUrl(fileIdsAndResponses.drop(filesInOneRequestToFilestorage).take(1)))
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 11 files - Makes three requests - List of 11 is returned`() {
		val numberOfFiles = 11
		mockFilestorageIsWorking(fileIdsAndResponses.take(filesInOneRequestToFilestorage))
		mockFilestorageIsWorking(fileIdsAndResponses.drop(filesInOneRequestToFilestorage).take(filesInOneRequestToFilestorage))
		mockFilestorageIsWorking(fileIdsAndResponses.drop(filesInOneRequestToFilestorage * 2).take(1))
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		val files = filestorageService.getFilesFromFilestorage(key, soknadarkivschema)

		assertEquals(numberOfFiles, files.size)
		assertFileContentIsCorrect(files)
		verifyMockedGetRequests(1, makeUrl(fileIdsAndResponses.take(filesInOneRequestToFilestorage)))
		verifyMockedGetRequests(1, makeUrl(fileIdsAndResponses.drop(filesInOneRequestToFilestorage).take(filesInOneRequestToFilestorage)))
		verifyMockedGetRequests(1, makeUrl(fileIdsAndResponses.drop(filesInOneRequestToFilestorage * 2).take(1)))
	}

	@Test
	fun `getFilesFromFilestorage - Filestorage responds with 409 Conflict - will throw exception`() {
		mockFilestorageRespondsConflict()
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(3).map { it.first })

		assertThrows<ArchivingException> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 3 files - Filestorage is down - will throw exception`() {
		val numberOfFiles = 3
		mockFilestorageIsDown()
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		assertThrows<ArchivingException> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 3 files - One of the files have been deleted - will throw FilesAlreadyDeletedException`() {
		val numberOfFiles = 3
		mockRequestedFileIsGone()
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		val e = assertThrows<Exception> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
		assertTrue(e.cause is FilesAlreadyDeletedException)
	}

	@Test
	fun `deleteFilesFromFilestorage - Deleting 0 files - Makes one request`() {
		val numberOfFiles = 0
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verifyMockedDeleteRequests(1, makeUrl(fileIdsAndResponses.take(numberOfFiles)))
	}

	@Test
	fun `deleteFilesFromFilestorage - Deleting 1 files - Makes one request`() {
		val numberOfFiles = 1
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verifyMockedDeleteRequests(1, makeUrl(fileIdsAndResponses.take(numberOfFiles)))
	}

	@Test
	fun `deleteFilesFromFilestorage - Deleting 11 files - Makes one request`() {
		val numberOfFiles = 11
		mockFilestorageDeletionIsWorking(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verifyMockedDeleteRequests(1, makeUrl(fileIdsAndResponses.take(numberOfFiles)))
	}

	@Test
	fun `deleteFilesFromFilestorage - Deleting 1 files - Filestorage is down - throws no exception`() {
		val numberOfFiles = 1
		mockFilestorageDeletionIsNotWorking()
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verifyMockedDeleteRequests(1, makeUrl(fileIdsAndResponses.take(numberOfFiles)))
	}


	private fun assertFileContentIsCorrect(files: List<FileData>) {
		assertAll("All files should have the right content",
			files.map { result -> {
				assertEquals(fileIdsAndResponses.first { it.first == result.id }.second, result.content?.map { it.toInt().toChar() }?.joinToString(""))
			} })
	}

	private fun makeUrl(fileIdsAndResponses: List<Pair<String, String>>) =
		appConfiguration.config.filestorageUrl + fileIdsAndResponses.joinToString(",") { it.first }

	private fun mockNumberOfFilesAndPerformRequest(numberOfFiles: Int): List<FileData> {
		mockFilestorageIsWorking(fileIdsAndResponses.take(numberOfFiles))
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })

		return filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
	}
}
