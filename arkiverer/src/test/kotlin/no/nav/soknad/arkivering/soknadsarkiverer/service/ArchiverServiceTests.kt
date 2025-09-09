package no.nav.soknad.arkivering.soknadsarkiverer.service

import io.mockk.*
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.*
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.util.translate
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class ArchiverServiceTests {

	private lateinit var metrics: ArchivingMetrics

	private val innsendingApi = mockk<InnsendingService>().also {
		every {
			it.getFilesFromFilestorage(any(), any())
		} returns FetchFileResponse(
			status = "ok",
			listOf(FileInfo("id", "content".toByteArray(), ResponseStatus.Ok)), exception = null
		)
	}
	private val innsendingApiNotFound = mockk<InnsendingService>().also {
		every {
			it.getFilesFromFilestorage(any(), any())
		} returns FetchFileResponse(
			status = "not-found",
			files = null, exception = null
		)
	}

	private val journalpostClient = mockk<JournalpostClientInterface>().also {
		every { it.opprettJournalpost(any(), any(), any()) } returns UUID.randomUUID().toString()
	}

	private val kafkaPublisher = mockk<KafkaPublisher>().also {
		every { it.putMetricOnTopic(any(), any(), any()) } just Runs
		every { it.putMessageOnTopic(any(), any(), any()) } just Runs
	}

	private val key = UUID.randomUUID().toString()

	private lateinit var archiverService: ArchiverService

	@BeforeEach
	fun setup() {
		metrics = ArchivingMetrics(PrometheusRegistry.defaultRegistry)
	}

	@AfterEach
	fun tearDown() {
		metrics.unregister()
	}

	@Test
	fun `Archiving already archived application throws exception`() {
		archiverService = ArchiverService(innsendingApiNotFound, journalpostClient, metrics, kafkaPublisher)

		val key2 = UUID.randomUUID().toString()
		mockAlreadyArchivedException(key2)

		val soknadschema = createSoknadarkivschema()
		val translatedSoknadschema = translate(soknadschema)

		CoroutineScope(Dispatchers.Default).launch {
			assertThrows<ApplicationAlreadyArchivedException> {
				archiverService.archive(key2, translatedSoknadschema, archiverService.fetchFiles(key, translatedSoknadschema))
			}
		}
	}

	var filer = slot<List<FileInfo>>()
	private val journalpostClient2 = mockk<JournalpostClientInterface>().also {
		every { it.opprettJournalpost(any(), any(), capture(filer)) } returns UUID.randomUUID().toString()
	}

	@Test
	fun `Fetch file metrics test`() {
		archiverService = ArchiverService(innsendingApi, journalpostClient2, metrics, kafkaPublisher)
		val key = UUID.randomUUID().toString()
		val tema = "AAP"
		val soknadschema =
			translate(createSoknadarkivschema(
				behandlingsId = key,
				tema = tema,
				fileIds = listOf(
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString(),
					UUID.randomUUID().toString()
				)
			)
			)

		runBlocking {
			archiverService.fetchFiles(key, soknadschema)

			val fetchObservation = metrics.getFileFetchSize()
			assertEquals(7.0, fetchObservation[0].sum)
			val fetchFileHistogram = metrics.getFileFetchSizeHistogram(tema)
			assertTrue(fetchFileHistogram != null)
			assertEquals("content".length.toDouble(), fetchFileHistogram?.sum)
		}
	}

	@Test
	fun `Archiving succeeds when all is up and running`() {
		archiverService = ArchiverService(innsendingApi, journalpostClient2, metrics, kafkaPublisher)
		val key = UUID.randomUUID().toString()
		val soknadschema = translate(createSoknadarkivschema())

		CoroutineScope(Dispatchers.Default).launch {
			archiverService.archive(key, soknadschema, archiverService.fetchFiles(key, soknadschema))

			verify(exactly = 1) { innsendingApi.getFilesFromFilestorage(eq(key), eq(soknadschema)) }
			verify(exactly = 1) { journalpostClient2.opprettJournalpost(eq(key), eq(soknadschema), any()) }
			assertTrue(filer.isCaptured)
			assertEquals(soknadschema.dokumenter.first().varianter.size, filer.captured.size)
		}

	}

	@Test
	fun `Archiving fails when no files is found`() {
		archiverService =
			ArchiverService(innsendingApiNotFound, journalpostClient, metrics, kafkaPublisher)

		val key = UUID.randomUUID().toString()
		val soknadschema = translate(createSoknadarkivschema())
		CoroutineScope(Dispatchers.Default).launch {
			assertThrows<ArchivingException> {
				archiverService.archive(key, soknadschema, archiverService.fetchFiles(key, soknadschema))
			}
		}
	}

	private fun mockAlreadyArchivedException(key: String) {
		every {
			journalpostClient.opprettJournalpost(
				eq(key),
				any(),
				any()
			)
		} throws ApplicationAlreadyArchivedException("Already archived")
	}
}
