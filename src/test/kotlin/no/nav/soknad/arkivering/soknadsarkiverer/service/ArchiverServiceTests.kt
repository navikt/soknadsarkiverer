package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.HealthCheck
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class ArchiverServiceTests {

	private val filestorage = mock<FileserviceInterface>()
	private val journalpostClient = mock<JournalpostClientInterface>()
	private val kafkaPublisher = mock<KafkaPublisher>()
	private val appConfiguration = AppConfiguration()
	private val healthCheck = HealthCheck(appConfiguration)

	private val key = UUID.randomUUID().toString()

	private lateinit var archiverService: ArchiverService

	@BeforeEach
	fun setup() {
		archiverService = ArchiverService(appConfiguration, filestorage, journalpostClient, kafkaPublisher)
	}

	@Test
	fun `Shutdown requested during protected segment - will still publish Archived event`() {
		mockShutdownSignalWhileSendingToJoark()

		archiverService.archive(key, createSoknadarkivschema())

		verify(kafkaPublisher, times(1)).putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.ARCHIVED)), any())
	}

	@Test
	fun `Shutdown requested before protected segment - will not call Joark or publish Archived event`() {
		mockShutdownSignalWhenStartingToArchive()

		archiverService.archive(key, createSoknadarkivschema())

		verify(journalpostClient, times(0)).opprettJournalpost(eq(key), any(), any())
		verify(kafkaPublisher, times(0)).putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.ARCHIVED)), any())
	}


	private fun mockShutdownSignalWhileSendingToJoark() {
		whenever(journalpostClient.opprettJournalpost(eq(key), any(), any()))
			.thenAnswer {
				val returnedJournalpostId = UUID.randomUUID().toString()
				signalShutdown()
				returnedJournalpostId
			}
	}

	private fun mockShutdownSignalWhenStartingToArchive() {
		whenever(kafkaPublisher.putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.STARTED)), any()))
			.then {
				signalShutdown()
				assertIsShuttingDown()
			}
	}

	private fun signalShutdown() {
		GlobalScope.launch { healthCheck.stop() }
	}

	private fun assertIsShuttingDown() {
		loopAndVerify(1, { if (appConfiguration.state.stopping) 1 else 0 })
	}
}
