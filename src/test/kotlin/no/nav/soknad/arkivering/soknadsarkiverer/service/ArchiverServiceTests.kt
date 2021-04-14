package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.ARCHIVED
import no.nav.soknad.arkivering.avroschemas.EventTypes.STARTED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.HealthCheck
import no.nav.soknad.arkivering.soknadsarkiverer.utils.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.ArgumentMatchers.anyList
import java.util.*
import java.util.concurrent.Semaphore

class ArchiverServiceTests {

	private val filestorage = mock<FileserviceInterface>()
	private val journalpostClient = mock<JournalpostClientInterface>()
	private val kafkaPublisher = mock<KafkaPublisher>()
	private val appConfiguration = AppConfiguration()
	private val metrics = mock<ArchivingMetrics>()

	private val key = UUID.randomUUID().toString()

	private lateinit var archiverService: ArchiverService

	@BeforeEach
	fun setup() {
		archiverService = ArchiverService(appConfiguration, filestorage, journalpostClient, kafkaPublisher)
	}

	@Test
	fun `Archiving already archived application throws exception`() {
		val key2 = UUID.randomUUID().toString()
		mockAlreadyArchivedException(key2)

		val soknadschema =  createSoknadarkivschema()
		assertThrows<ApplicationAlreadyArchivedException> {
			archiverService.archive(key2, soknadschema, archiverService.fetchFiles(key, soknadschema))
		}

	}

	@Test
	fun `Archiving succeeds when all is up and running`() {
		val key = UUID.randomUUID().toString()
		val soknadschema =  createSoknadarkivschema()
		archiverService.archive(key, soknadschema, archiverService.fetchFiles(key, soknadschema))
		verify(filestorage, times(1)).getFilesFromFilestorage(eq(key), eq(soknadschema))
		verify(journalpostClient, times(1)).opprettJournalpost(eq(key), eq(soknadschema), anyList())

	}

	private fun mockAlreadyArchivedException(key: String) {
		whenever(journalpostClient.opprettJournalpost(eq(key), any(), any()))
			.thenThrow(ApplicationAlreadyArchivedException("Already archived"))
	}
}
