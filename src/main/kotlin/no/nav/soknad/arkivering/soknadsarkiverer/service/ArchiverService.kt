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

	fun archive(key: String, data: Soknadarkivschema) {
		try {
			createProcessingEvent(key, STARTED)
			val files = getFilesFromFilestorage(key, data)

			val journalpostId = sendToArchive(key, data, files)
			logger.info("$key: Opprettet journalpostId=${journalpostId} for behandlingsid=${data.getBehandlingsid()}")

			deleteFilesFromFilestorage(key, data)
			createProcessingEvent(key, FINISHED)
			createMessage(key, "ok")

		} catch (e: ShuttingDownException) {
			logger.warn("$key: Will not start to archive - application is shutting down.")

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	private fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FilElementDto> {
		val startTime = System.currentTimeMillis()
		val files = filestorageService.getFilesFromFilestorage(key, data)
		createMetric(key, "get files from filestorage", startTime)
		return files
	}

	private fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema) {
		val startTime = System.currentTimeMillis()
		filestorageService.deleteFilesFromFilestorage(key, data)
		createMetric(key, "delete files from filestorage", startTime)
	}

	private fun sendToArchive(key: String, data: Soknadarkivschema, files: List<FilElementDto>): String {
		return protectFromShutdownInterruption(appConfiguration) {
			val startTime = System.currentTimeMillis()

			val journalpostId = journalpostClient.opprettJournalpost(key, data, files)
			createProcessingEvent(key, ARCHIVED)
			createMetric(key, "send files to archive", startTime)

			journalpostId
		}
	}


	private fun createProcessingEvent(key: String, type: EventTypes) {
		kafkaPublisher.putProcessingEventOnTopic(key, ProcessingEvent(type))
	}

	private fun createMessage(key: String, message: String) {
		kafkaPublisher.putMessageOnTopic(key, message)
	}

	private fun createMetric(key: String, message: String, startTime: Long) {

		kafkaPublisher.putMetricOnTopic(key,
			"{" +
				"\"application\":\"soknadsarkiverer\"," +
				"\"action\":\"$message\"," +
				"\"startTime\":$startTime," +
				"\"duration\":${System.currentTimeMillis() - startTime}" +
				"}"
		)
	}

	private fun createExceptionMessage(e: Exception): String {
		val sw = StringWriter()
		e.printStackTrace(PrintWriter(sw))
		val stacktrace = sw.toString()

		return "Exception when archiving: '" + e.message + "'\n" + stacktrace
	}
}
