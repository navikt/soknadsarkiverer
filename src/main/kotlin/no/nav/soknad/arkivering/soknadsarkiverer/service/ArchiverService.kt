package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ShuttingDownException
import no.nav.soknad.arkivering.soknadsarkiverer.config.protectFromShutdownInterruption
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter

@Service
class ArchiverService(private val appConfiguration: AppConfiguration,
											private val filestorageService: FileserviceInterface,
											private val journalpostClient: JournalpostClientInterface,
											private val kafkaPublisher: KafkaPublisher) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(key: String, data: Soknadarkivschema, files: List<FilElementDto>) {
		try {
//			val files = filestorageService.getFilesFromFilestorage(key, data)

//			val journalpostId = protectFromShutdownInterruption(appConfiguration) {
				val journalpostId = journalpostClient.opprettJournalpost(key, data, files)
//				journalpostId
//			}
			logger.info("$key: Opprettet journalpostId=${journalpostId} for behandlingsid=${data.getBehandlingsid()}")

//		} catch (e: ShuttingDownException) {
//			logger.warn("$key: Will not start to archive - application is shutting down.")

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	fun fetchFiles(key: String, data: Soknadarkivschema): List<FilElementDto> {
		try {

			return filestorageService.getFilesFromFilestorage(key, data)

		} catch (e: ShuttingDownException) {
			logger.warn("$key: Will not start to fetchFiles - application is shutting down.")
			return ArrayList()

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	fun deleteFiles(key: String, data: Soknadarkivschema) {
		try {

			filestorageService.deleteFilesFromFilestorage(key, data)
			createMessage(key, "ok")

		} catch (e: ShuttingDownException) {
			logger.warn("$key: Will not start to deleteFiles - application is shutting down.")

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	private fun createMessage(key: String, message: String) {
		kafkaPublisher.putMessageOnTopic(key, message)
	}

	private fun createExceptionMessage(e: Exception): String {
		val sw = StringWriter()
		e.printStackTrace(PrintWriter(sw))
		val stacktrace = sw.toString()

		return "Exception when archiving: '" + e.message + "'\n" + stacktrace
	}
}
