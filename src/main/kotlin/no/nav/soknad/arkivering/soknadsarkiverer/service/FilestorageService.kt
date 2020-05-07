package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.soknadarkivering.avroschemas.Soknadarkivschema
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.RequestEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URI

@Service
class FilestorageService(private val restTemplate: RestTemplate,
												 private val appConfiguration: AppConfiguration) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun getFilesFromFilestorage(data: Soknadarkivschema): List<FilElementDto> {
		try {
			val url = constructFilestorageUrl(data)
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

	fun deleteFilesFromFilestorage(data: Soknadarkivschema?) {
		try {
			if (data == null) {
				logger.warn("About to delete files from file storage, but found no ids to delete.")
				return
			}
			val url = constructFilestorageUrl(data)
			logger.info("Deleting data from file storage via: '$url'")

			restTemplate.delete(url)
			logger.info("Deleted these ids from file storage: ${getAllUuids(data)}")

		} catch (e: Exception) {
			val ids = if (data == null) "null" else getAllUuids(data)
			logger.warn("Failed to delete files from file storage. Everything is saved to Joark correctly, " +
				"so this error will be ignored. Affected file ids: '$ids'", e)
		}
	}

	private fun constructFilestorageUrl(data: Soknadarkivschema): String {
		val ids = getAllUuids(data)

		return appConfiguration.config.filestorageHost + appConfiguration.config.filestorageUrl + ids
	}

	private fun getAllUuids(data: Soknadarkivschema): String {
		return data.getMottatteDokumenter()
			.flatMap { it.getMottatteVarianter().map { variant -> variant.getUuid() } }
			.joinToString(",")
	}
}

inline fun <reified T : Any> typeRef(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}
