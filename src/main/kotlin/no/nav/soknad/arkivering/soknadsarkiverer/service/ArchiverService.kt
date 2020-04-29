package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.config.KafkaProcessingEventProducer
import no.nav.soknad.arkivering.soknadsarkiverer.converter.createJoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkData
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ArchiverService(private val filestorageService: FilestorageService, private val joarkArchiver: JoarkArchiver,
											private val kafkaProcessingEventProducer: KafkaProcessingEventProducer) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(key: String, data: Soknadarkivschema) {
		createStartedEvent(key)

		val files = filestorageService.getFilesFromFilestorage(data)
		val joarkData = convertToJoarkData(data, files)

		joarkArchiver.putDataInJoark(joarkData)
		filestorageService.deleteFilesFromFilestorage(data)

		createFinishedEvent(key)
	}

	private fun convertToJoarkData(data: Soknadarkivschema, files: List<FilElementDto>): JoarkData {
		try {
			return createJoarkData(data, files)
		} catch (e: Exception) {
			logger.error("Error when converting message.", e)
			throw e
		}
	}

	private fun createStartedEvent(key: String) {
		return createProcessingTopicEvent(key, "STARTED")
	}

	private fun createFinishedEvent(key: String) {
		return createProcessingTopicEvent(key, "ENDED")
	}

	private fun createProcessingTopicEvent(key: String, type: String) {
		kafkaProcessingEventProducer.putDataOnTopic(key, type)
	}
}
