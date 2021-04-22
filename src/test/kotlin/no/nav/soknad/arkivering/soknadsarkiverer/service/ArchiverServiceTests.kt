package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyList
import java.util.*

class ArchiverServiceTests {

	private val filestorage = mock<FileserviceInterface>()
	private val journalpostClient = mock<JournalpostClientInterface>()
	private val kafkaPublisher = mock<KafkaPublisher>()

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
		verify(filestorage, times(1)).getFilesFromFilestorage(eq(key), eq(soknadschema))
		verify(journalpostClient, times(1)).opprettJournalpost(eq(key), eq(soknadschema), anyList())
	}

	private fun mockAlreadyArchivedException(key: String) {
		whenever(journalpostClient.opprettJournalpost(eq(key), any(), any()))
			.thenThrow(ApplicationAlreadyArchivedException("Already archived"))
	}
}
