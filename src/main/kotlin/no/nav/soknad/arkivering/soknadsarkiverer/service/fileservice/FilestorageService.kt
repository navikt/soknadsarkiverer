package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import org.apache.tomcat.util.codec.binary.Base64
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class FilestorageService(@Qualifier("basicRestTemplate") private val restTemplate: RestTemplate,
												 private val appConfiguration: AppConfiguration) : FileserviceInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FilElementDto> {
		try {
			val fileIds = getFileIds(data)
			logger.info("$key: Getting files with ids: '$fileIds'")

			val files = getFiles(fileIds)

			logger.info("$key: Received files.size: ${files?.size}")
			return files ?: return arrayListOf()

		} catch (e: Exception) {
			logger.error("$key: Error retrieving files from file storage", e)
			throw ArchivingException(e)
		}
	}

	override fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema) {
		val fileIds = getFileIds(data)
		try {

			logger.info("$key: Calling file storage to delete '$fileIds'")
			deleteFiles(fileIds)
			logger.info("$key: The files: $fileIds are deleted")

		} catch (e: Exception) {
			logger.warn("$key: Failed to delete files from file storage. Everything is saved to Joark correctly, " +
				"so this error will be ignored. Affected file ids: '$fileIds'", e)
		}
	}

	private fun createHeaders(username: String, password: String): HttpHeaders {
		return object : HttpHeaders() {
			init {
				val auth = "$username:$password"
				val encodedAuth: ByteArray = Base64.encodeBase64(auth.toByteArray())
				val authHeader = "Basic " + String(encodedAuth)
				set("Authorization", authHeader)
				set(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				set(ACCEPT, MediaType.APPLICATION_JSON_VALUE)
			}
		}
	}

	private fun getFiles(fileIds: String): List<FilElementDto>? {
		val response = performRestCall(fileIds, HttpMethod.GET, typeRef<List<FilElementDto>>())
		return response.body
	}

	private fun deleteFiles(fileIds: String) {
		performRestCall(fileIds, HttpMethod.DELETE, typeRef<String>())
	}

	private fun <T> performRestCall(fileIds: String, method: HttpMethod, type: ParameterizedTypeReference<T>): ResponseEntity<T> {
		val username = appConfiguration.config.username
		val sharedPassword = appConfiguration.config.sharedPassword
		val url = appConfiguration.config.filestorageHost + appConfiguration.config.filestorageUrl + fileIds

		val request = HttpEntity<Any>(url, createHeaders(username, sharedPassword))
		return restTemplate.exchange(url, method, request, type)
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.getMottatteDokumenter()
			.flatMap { it.getMottatteVarianter().map { variant -> variant.getUuid() } }
			.joinToString(",")


	private inline fun <reified T : Any> typeRef(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}
}
