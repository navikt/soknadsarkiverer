package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import io.mockk.*
import io.prometheus.client.Summary
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.*

class FilestorageServiceTests {

	private val filesApi = mockk<FilesApi>()
	private val healthApi = mockk<HealthApi>()
	private val metrics = mockk<ArchivingMetrics>()
	private val filestorageService = FilestorageService(filesApi, healthApi, metrics)

	private lateinit var fileIdsAndResponses: List<Pair<String, String>>
	private val key = UUID.randomUUID().toString()


	@BeforeEach
	fun setup() {
		every { metrics.filestorageGetLatencyStart() } returns summaryTimer()
		every { metrics.filestorageDelLatencyStart() } returns summaryTimer()
		every { metrics.endTimer(any()) } just runs
		every { metrics.incGetFilestorageSuccesses() } just runs
		every { metrics.incGetFilestorageErrors() } just runs
		every { metrics.incDelFilestorageSuccesses() } just runs
		every { metrics.incDelFilestorageErrors() } just runs

		fileIdsAndResponses = (0 until 100).map { number -> UUID.randomUUID().toString() to number.toString() }
	}


	@Test
	fun `getFilesFromFilestorage - Asking for 0 files - Makes 0 requests - Empty list is returned`() {
		val numberOfFiles = 0
		val soknadarkivschema = mockFindFilesAndCreateSoknadarkivschema(numberOfFiles)

		val files = filestorageService.getFilesFromFilestorage(key, soknadarkivschema)

		assertEquals(numberOfFiles, files.size)
		assertFileContentIsCorrect(files)
		verify(exactly = 0) { filesApi.findFilesByIds(any(), any(), any()) }
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 6 files - Makes 6 requests - List of 6 is returned`() {
		val numberOfFiles = 6
		val soknadarkivschema = mockFindFilesAndCreateSoknadarkivschema(numberOfFiles)

		val files = filestorageService.getFilesFromFilestorage(key, soknadarkivschema)

		assertEquals(numberOfFiles, files.size)
		assertFileContentIsCorrect(files)
		verify(exactly = numberOfFiles) { filesApi.findFilesByIds(any(), any(), any()) }
	}

	@Test
	fun `getFilesFromFilestorage - Filestorage responds with different statuses - will throw exception`() {
		val statusesInResponse = listOf("ok", "not-found", "deleted")
		val soknadarkivschema = mockFindFilesAndCreateSoknadarkivschema(3, statusesInResponse)

		assertThrows<ArchivingException> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 3 files - Filestorage is down - will throw exception`() {
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(3).map { it.first })
		every { filesApi.findFilesByIds(any(), any(), any()) } throws ArchivingException(RuntimeException("mocked exception"))

		assertThrows<ArchivingException> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 3 file - the files has been deleted - will throw FilesAlreadyDeletedException`() {
		val soknadarkivschema = mockFindFilesAndCreateSoknadarkivschema(3, statusesInResponse = listOf("deleted"))

		val e = assertThrows<Exception> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
		assertTrue(e.cause is FilesAlreadyDeletedException)
	}

	@Test
	fun `getFilesFromFilestorage - Asking for 1 file - the file has never been seen - will throw Exception`() {
		val soknadarkivschema = mockFindFilesAndCreateSoknadarkivschema(1, statusesInResponse = listOf("not-found"))

		assertThrows<Exception> {
			filestorageService.getFilesFromFilestorage(key, soknadarkivschema)
		}
	}


	@Test
	fun `deleteFilesFromFilestorage - Deleting 0 files - Makes one request`() {
		val numberOfFiles = 0
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		every { filesApi.deleteFiles(any(), any()) } returns Unit

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verify(exactly = 1) { filesApi.deleteFiles(any(), any()) }
	}

	@Test
	fun `deleteFilesFromFilestorage - Deleting 17 files - Makes one request`() {
		val numberOfFiles = 17
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		every { filesApi.deleteFiles(any(), any()) } returns Unit

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verify(exactly = 1) { filesApi.deleteFiles(fileIdsAndResponses.take(numberOfFiles).map { it.first }, any()) }
	}

	@Test
	fun `deleteFilesFromFilestorage - Deleting 1 files - Filestorage is down - throws no exception`() {
		val numberOfFiles = 1
		val soknadarkivschema = createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })
		every { filesApi.deleteFiles(any(), any()) } throws Exception("Mocked exception")

		filestorageService.deleteFilesFromFilestorage(key, soknadarkivschema)

		verify(exactly = 1) { filesApi.deleteFiles(fileIdsAndResponses.take(numberOfFiles).map { it.first }, any()) }
	}


	private fun assertFileContentIsCorrect(files: List<FileData>) {
		assertAll("All files should have the right content",
			files.map { result ->
				{
					assertEquals(
						fileIdsAndResponses.first { it.first == result.id }.second,
						result.content?.map { it.toInt().toChar() }?.joinToString("")
					)
				}
			})
	}


	private fun mockFindFilesAndCreateSoknadarkivschema(
		numberOfFiles: Int,
		statusesInResponse: List<String> = listOf("ok")
	): Soknadarkivschema {

		fileIdsAndResponses.take(numberOfFiles).forEachIndexed { index, idAndResponse ->
			val status = statusesInResponse[index % statusesInResponse.size]
			val (id, response) = idAndResponse

			every { filesApi.findFilesByIds(listOf(id), any(), any()) } returns
				listOf(FileData(id, response.toByteArray(), OffsetDateTime.now(), status))
		}

		return createSoknadarkivschema(fileIdsAndResponses.take(numberOfFiles).map { it.first })
	}

	private fun summaryTimer(): Summary.Timer {
		val allowedChars = ('A'..'Z') + ('a'..'z')
		val name = (1..10)
			.map { allowedChars.random() }
			.joinToString("")

		return Summary.build().name(name).help(name).register().startTimer()
	}
}
