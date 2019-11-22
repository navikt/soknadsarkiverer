package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import kotlin.ByteArray

@Service
class FileStorageRetrievingService(private val restTemplate: RestTemplate,
																	 private val applicationProperties: ApplicationProperties) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun getFilesFromFileStorage(archivalData: ArchivalData): List<ByteArray> {
		try {
			logger.info("Getting data from file storage: '$archivalData'")
			val url = applicationProperties.filestorageHost + applicationProperties.filestorageUrl + archivalData.message

			return getFiles(url)

		} catch (e: Exception) {
			logger.error("Error retrieving files from file storage", e)
			throw e
		}
	}

	private fun getFiles(url: String): List<ByteArray> {
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_JSON
		headers.accept = listOf(MediaType.APPLICATION_OCTET_STREAM)

		restTemplate.messageConverters.add(ByteArrayHttpMessageConverter())

		val response = restTemplate.getForEntity(url, ByteArray::class.java).body
		return if (response != null) {
			listOf(response) //TODO: Handle properly
		} else
			throw Exception("Received no files")
	}
}
