package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationProperties
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.RequestEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URI

@Service
class FileStorageRetrievingService(private val restTemplate: RestTemplate,
																	 private val applicationProperties: ApplicationProperties) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun getFilesFromFileStorage(data: Soknadarkivschema): List<FilElementDto> {
		try {
			val url = applicationProperties.filestorageHost + applicationProperties.filestorageUrl + data.getBehandlingsid()
			logger.info("Getting data from file storage via: '$url'")

			val files = getFiles(url)
			logger.info("Received: $files")
			return files

		} catch (e: Exception) {
			logger.error("Error retrieving files from file storage", e)
			throw e
		}
	}

	private fun getFiles(url: String): List<FilElementDto> {
		val request = RequestEntity<Any>(HttpMethod.GET, URI(url))

		val response = restTemplate.exchange(request, typeRef<List<FilElementDto>>()).body

		return response ?: throw Exception("Received no files")
	}
}

inline fun <reified T : Any> typeRef(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}
