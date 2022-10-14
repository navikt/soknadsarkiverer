package no.nav.soknad.arkivering.soknadsarkiverer.service

import io.mockk.*
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime.now
import java.util.*

class ArchiverServiceTests {

	private val filestorage = mockk<FileserviceInterface>().also {
		every { it.getFilesFromFilestorage(any(), any()) } returns listOf(FileData("id", "content".toByteArray(), now(), "ok"))
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
		archiverService = ArchiverService(filestorage, journalpostClient, kafkaPublisher)
	}

	@Test
	fun `Archiving already archived application throws exception`() {
		val key2 = UUID.randomUUID().toString()
		mockAlreadyArchivedException(key2)

		val soknadschema = createSoknadarkivschema()
		assertThrows<ApplicationAlreadyArchivedException> {
			archiverService.archive(key2, soknadschema, archiverService.fetchFiles(key, soknadschema))
		}
	}

	@Test
	fun `Archiving succeeds when all is up and running`() {
		val key = UUID.randomUUID().toString()
		val soknadschema = createSoknadarkivschema()

		archiverService.archive(key, soknadschema, archiverService.fetchFiles(key, soknadschema))

		verify(exactly = 1) { filestorage.getFilesFromFilestorage(eq(key), eq(soknadschema)) }
		verify(exactly = 1) { journalpostClient.opprettJournalpost(eq(key), eq(soknadschema), any()) }
	}

	private fun mockAlreadyArchivedException(key: String) {
		every { journalpostClient.opprettJournalpost(eq(key), any(), any()) } throws ApplicationAlreadyArchivedException("Already archived")
	}
}
