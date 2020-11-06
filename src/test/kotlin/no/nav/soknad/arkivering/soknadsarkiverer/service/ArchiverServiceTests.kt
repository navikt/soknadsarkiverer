package no.nav.soknad.arkivering.soknadsarkiverer.service

import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.ARCHIVED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.HealthCheck
import no.nav.soknad.arkivering.soknadsarkiverer.utils.createSoknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.utils.loopAndVerify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.concurrent.Semaphore

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
		sendShutdownSignalWhileSendingToJoark()

		archiverService.archive(key, createSoknadarkivschema())

		assertEquals(0, appConfiguration.state.busyCounter)
		verify(kafkaPublisher, times(1)).putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(ARCHIVED)), any())
	}

	@Test
	fun `Shutdown requested before protected segment - will not call Joark or publish Archived event`() {
		sendShutdownSignalWhenStartingToArchive()

		archiverService.archive(key, createSoknadarkivschema())

		assertEquals(0, appConfiguration.state.busyCounter)
		verify(journalpostClient, times(0)).opprettJournalpost(eq(key), any(), any())
		verify(kafkaPublisher, times(0)).putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(ARCHIVED)), any())
	}

	@Test
	fun `Protected segment throws exception - busyCounter is still 0 afterwards`() {
		mockExceptionIsThrownWhileSendingToJoark()

		assertThrows<RuntimeException> {
			archiverService.archive(key, createSoknadarkivschema())
		}

		assertEquals(0, appConfiguration.state.busyCounter)
	}

	@Test
	fun `If Shutdown is requested during protected segment of one event, a second may not enter protected segment afterwards`() {
		val key1 = UUID.randomUUID().toString()
		val key2 = UUID.randomUUID().toString()
		val event2StartLock = Semaphore(1).also { it.acquire() }

		sendShutdownSignalWhileSendingToJoarkAndReleaseLockForSecondEvent(key1, event2StartLock)
		mockDelayWhenStartingToArchive(key2, event2StartLock)

		runBlocking {
			val task1 = async { archiverService.archive(key1, createSoknadarkivschema()) }
			val task2 = async { archiverService.archive(key2, createSoknadarkivschema()) }
			awaitAll(task1, task2)
		}

		assertEquals(0, appConfiguration.state.busyCounter)
		verify(kafkaPublisher, times(1)).putProcessingEventOnTopic(eq(key1), eq(ProcessingEvent(ARCHIVED)), any()) // First event finishes fine
		verify(kafkaPublisher, times(0)).putProcessingEventOnTopic(eq(key2), eq(ProcessingEvent(ARCHIVED)), any()) // Second event did not enter protected segment
	}


	private fun mockExceptionIsThrownWhileSendingToJoark() {
		whenever(journalpostClient.opprettJournalpost(eq(key), any(), any()))
			.thenThrow(RuntimeException("Mocked exception"))
	}

	private fun sendShutdownSignalWhileSendingToJoark() {
		whenever(journalpostClient.opprettJournalpost(eq(key), any(), any()))
			.thenAnswer {
				val returnedJournalpostId = UUID.randomUUID().toString()
				signalShutdown()
				returnedJournalpostId
			}
	}

	private fun sendShutdownSignalWhenStartingToArchive() {
		whenever(kafkaPublisher.putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.STARTED)), any()))
			.then {
				signalShutdown()
			}
	}

	private fun sendShutdownSignalWhileSendingToJoarkAndReleaseLockForSecondEvent(key: String, lock: Semaphore) {
		whenever(journalpostClient.opprettJournalpost(eq(key), any(), any()))
			.thenAnswer {
				val returnedJournalpostId = UUID.randomUUID().toString()
				signalShutdown()
				lock.release() // Release lock, allowing second event to proceed
				returnedJournalpostId
			}
	}

	private fun mockDelayWhenStartingToArchive(key: String, lock: Semaphore) {
		whenever(kafkaPublisher.putProcessingEventOnTopic(eq(key), eq(ProcessingEvent(EventTypes.STARTED)), any()))
			.then {
				lock.acquire() // Only proceed when it is possible to acquire lock
			}
	}

	private fun signalShutdown() {
		GlobalScope.launch { healthCheck.stop() }
		loopAndVerify(1, { if (appConfiguration.state.stopping) 1 else 0 })
	}
}
