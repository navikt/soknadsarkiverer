package no.nav.soknad.arkivering.soknadsarkiverer.service

import io.mockk.*
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.*
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.InnsendingTopicMsgBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.TestDokument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class ArchiverServiceTests {

	private lateinit var metrics: ArchivingMetrics

	private val fetchedFiles = listOf(FileInfo("id", "content".toByteArray(), ResponseStatus.Ok))

	private val innsendingApi = mockk<InnsendingService>().also {
		every {
			it.getFilesFromFilestorage(any(), any())
		} returns FetchFileResponse(status = "ok", files = fetchedFiles, exception = null)
	}
	private val innsendingApiNotFound = mockk<InnsendingService>().also {
		every {
			it.getFilesFromFilestorage(any(), any())
		} returns FetchFileResponse(status = "not-found", files = null, exception = null)
	}

	private val archivedFiles = slot<List<FileInfo>>()
	private val journalpostClient = mockk<JournalpostClientInterface>().also {
		every { it.opprettJournalpost(any(), any(), capture(archivedFiles)) } returns UUID.randomUUID().toString()
	}

	private val kafkaPublisher = mockk<KafkaPublisher>().also {
		every { it.putMetricOnTopic(any(), any(), any()) } just Runs
		every { it.putMessageOnTopic(any(), any(), any()) } just Runs
	}

	@BeforeEach
	fun setup() {
		metrics = ArchivingMetrics(PrometheusRegistry.defaultRegistry)
	}

	@AfterEach
	fun tearDown() {
		metrics.unregister()
	}

	@Test
	fun `Archiving already archived application throws exception and publishes a message`() {
		val archiverService = ArchiverService(innsendingApi, journalpostClient, metrics, kafkaPublisher)
		val key = UUID.randomUUID().toString()
		val soknadschema = InnsendingTopicMsgBuilder().withInnsendingsId(key).build()
		every { journalpostClient.opprettJournalpost(eq(key), any(), any()) } throws ApplicationAlreadyArchivedException("Already archived")

		val files = runBlocking { archiverService.fetchFiles(key, soknadschema) }
		assertThrows<ApplicationAlreadyArchivedException> {
			archiverService.archive(key, soknadschema, files)
		}

		verify(exactly = 1) { kafkaPublisher.putMessageOnTopic(eq(key), match { it?.contains("Already archived") == true }, any()) }
		verify(exactly = 0) { kafkaPublisher.putMetricOnTopic(eq(key), match { it.action == "send files to archive" }, any()) }
	}

	@Test
	fun `Fetch file metrics test`() {
		val archiverService = ArchiverService(innsendingApi, journalpostClient, metrics, kafkaPublisher)
		val key = UUID.randomUUID().toString()
		val skjemanummer = "NAV 11-12.12"
		val tittel = "Apa bepa"
		val tema = "AAP"

		val hovedDokumentVarianter = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val kvitteringVariant = listOf(UUID.randomUUID().toString())
		val attachmentVariant = listOf(UUID.randomUUID().toString())
		val soknadschema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withInnsendingsId(key)
			.withArkivtema(tema)
			.withTestDokumenter(
				mutableListOf(
					TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = hovedDokumentVarianter ),
					TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = kvitteringVariant ),
					TestDokument( skjemanummer = "W1", erHovedskjema = false, tittel = "Attachment", uuids = attachmentVariant )
				)
			)
			.build()

		runBlocking {
			archiverService.fetchFiles(key, soknadschema)
		}

		val fetchSizeObservation = metrics.getFileFetchSize()
		assertEquals(7.0, fetchSizeObservation[0].sum)
		val fetchFileHistogram = metrics.getFileFetchSizeHistogram(tema)
		assertTrue(fetchFileHistogram != null)
		assertEquals("content".length.toDouble(), fetchFileHistogram?.sum)
		val fetchFileCount = metrics.getGetFilestorageSuccesses()
		assertEquals(1.0, fetchFileCount) // En gang pr vellykket forsøk på å hente en innsendings filer
	}

	@Test
	fun `Archiving succeeds when all is up and running`() {
		val archiverService = ArchiverService(innsendingApi, journalpostClient, metrics, kafkaPublisher)
		val key = UUID.randomUUID().toString()
		val soknadschema = InnsendingTopicMsgBuilder().withInnsendingsId(key).build()

		val files = runBlocking { archiverService.fetchFiles(key, soknadschema) }
		archiverService.archive(key, soknadschema, files)

		verify(exactly = 1) { innsendingApi.getFilesFromFilestorage(eq(key), eq(soknadschema)) }
		verify(exactly = 1) { journalpostClient.opprettJournalpost(eq(key), eq(soknadschema), any()) }
		assertEquals(fetchedFiles, archivedFiles.captured)
		verify(exactly = 1) { kafkaPublisher.putMetricOnTopic(eq(key), match { it.action == "send files to archive" }, any()) }
	}

	@Test
	fun `Archiving fails when no files is found`() {
		val archiverService = ArchiverService(innsendingApiNotFound, journalpostClient, metrics, kafkaPublisher)
		val key = UUID.randomUUID().toString()
		val soknadschema = InnsendingTopicMsgBuilder().withInnsendingsId(key).build()

		assertThrows<ArchivingException> {
			runBlocking { archiverService.fetchFiles(key, soknadschema) }
		}

		verify(exactly = 1) { kafkaPublisher.putMessageOnTopic(eq(key), match { it?.contains("Files not found") == true }, any()) }
		assertEquals(1.0, metrics.getGetFilestorageErrors())
	}
}
