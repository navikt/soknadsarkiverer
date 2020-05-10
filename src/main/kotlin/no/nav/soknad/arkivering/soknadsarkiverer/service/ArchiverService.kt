package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.converter.createJoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter

@Service
class ArchiverService(private val filestorageService: FileserviceInterface, private val joarkArchiver: JoarkArchiver,
											private val kafkaPublisher: KafkaPublisher) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(key: String, data: Soknadarkivschema) {
		try {
			createProcessingEvent(key, STARTED)

			val fileIds = getAllUuids(data)
			val files = filestorageService.getFilesFromFilestorage(fileIds)
			val joarkData = convertToJoarkData(data, files)

			joarkArchiver.putDataInJoark(joarkData)
			createProcessingEvent(key, ARCHIVED)
			filestorageService.deleteFilesFromFilestorage(fileIds)

			createProcessingEvent(key, FINISHED)
			createMessage(key, "ok")

		} catch (e: Exception) {

			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	private fun convertToJoarkData(data: Soknadarkivschema, files: List<FilElementDto>): JoarkData {
		try {
			return createJoarkData(data, files)
		} catch (e: Exception) {
			logger.error("Error when converting message.", e)
			throw e
		}
	}

	private fun createProcessingEvent(key: String, type: EventTypes) {
		kafkaPublisher.putProcessingEventOnTopic(key, ProcessingEvent(type))
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

	private fun getAllUuids(data: Soknadarkivschema): String {
		return data.getMottatteDokumenter()
			.flatMap { it.getMottatteVarianter().map { variant -> variant.getUuid() } }
			.joinToString(",")
	}
}
