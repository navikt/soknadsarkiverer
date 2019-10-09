package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.dto.JoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class JoarkArchiver(private val restTemplate: RestTemplate,
										private val applicationProperties: ApplicationProperties) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(archivalData: ArchivalData) {
		val attachedFiles = getFilesFromFileStorage(archivalData)
		val joarkData = createJoarkData(archivalData, attachedFiles)
		putDataInJoark(joarkData)
	}

	private fun getFilesFromFileStorage(archivalData: ArchivalData): List<ByteArray> {
		// TODO: fetch from file storage
		return listOf(archivalData.message.toByteArray()) // Just return something in the mean time
	}

	private fun createJoarkData(archivalData: ArchivalData, attachedFiles: List<ByteArray>) = JoarkData(archivalData.id, archivalData.message, attachedFiles)

	private fun putDataInJoark(joarkData: JoarkData) {
		logger.info("Sending to Joark: '$joarkData'")
		val url = applicationProperties.joarkHost + applicationProperties.joarkUrl
		restTemplate.postForObject(url, joarkData, String::class.java)
	}
}
