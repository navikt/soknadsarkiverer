package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class JoarkArchiver(private val restTemplate: RestTemplate,
										private val applicationProperties: ApplicationProperties) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(archivalData: ArchivalData) {
		putDataInJoark(archivalData)
	}

	private fun putDataInJoark(archivalData: ArchivalData) {
		logger.info("Sending to Joark: '$archivalData'")
		val url = applicationProperties.joarkHost + applicationProperties.joarkUrl
		restTemplate.postForObject(url, archivalData, String::class.java)
	}
}
