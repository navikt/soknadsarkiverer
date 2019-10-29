package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.dto.JoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class JoarkArchiver(private val restTemplate: RestTemplate,
										private val applicationProperties: ApplicationProperties) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(archivalData: ArchivalData) {
		val joarkData = MessageConverter().createJoarkData(archivalData, FileStorageRetrievingService().getFilesFromFileStorage(archivalData))
		putDataInJoark(joarkData)
	}

	fun putDataInJoark(joarkData: JoarkData) {
		try {
			logger.info("Sending to Joark: '$joarkData'")
			val url = applicationProperties.joarkHost + applicationProperties.joarkUrl

			sendDataToJoark(joarkData, url)

		} catch (e: Exception) {
			logger.error("Error sending to Joark", e)
			throw e
		}
	}

	private fun sendDataToJoark(joarkData: JoarkData, url: String) {
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_JSON
		val request = HttpEntity(joarkData, headers)
		restTemplate.postForObject(url, request, String::class.java)
	}
}
