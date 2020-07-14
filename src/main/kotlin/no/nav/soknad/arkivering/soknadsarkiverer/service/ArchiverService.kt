package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.service.converter.createJoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
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

			val files = filestorageService.getFilesFromFilestorage(key, data)
			val joarkData = convertToJoarkData(key, data, files)

			joarkArchiver.putDataInJoark(key, joarkData)
			createProcessingEvent(key, ARCHIVED)
			filestorageService.deleteFilesFromFilestorage(key, data)

			createProcessingEvent(key, FINISHED)
			createMessage(key, "ok")

		} catch (e: Exception) {

			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	private fun convertToJoarkData(key: String, data: Soknadarkivschema, files: List<FilElementDto>): JoarkData {
		try {
			return createJoarkData(data, files)
		} catch (e: Exception) {
			logger.error("$key: Error when converting message.", e)
			throw ArchivingException(e)
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
}
