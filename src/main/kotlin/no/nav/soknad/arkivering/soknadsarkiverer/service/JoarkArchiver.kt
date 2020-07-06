package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkData
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class JoarkArchiver(private val restTemplate: RestTemplate,
										private val appConfiguration: AppConfiguration) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun putDataInJoark(joarkData: JoarkData) {
		try {
			logger.info("Sending to Joark: '$joarkData'")
			val url = appConfiguration.config.joarkHost + appConfiguration.config.joarkUrl

			val response = sendDataToJoark(joarkData, url)

			logger.info("Saved to Joark. Got the following response:\n$response")

		} catch (e: Exception) {
			logger.error("Error sending to Joark", e)
			throw ArchivingException(e)
		}
	}

	private fun sendDataToJoark(joarkData: JoarkData, url: String): JoarkResponse? {
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_JSON
		val request = HttpEntity(joarkData, headers)
		return restTemplate.postForObject(url, request, JoarkResponse::class.java)
	}
}
