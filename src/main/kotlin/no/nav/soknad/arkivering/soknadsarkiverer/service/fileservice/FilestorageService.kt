package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import org.apache.tomcat.util.codec.binary.Base64
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions
import org.springframework.web.reactive.function.client.WebClient

@Service
class FilestorageService(@Qualifier("basicWebClient") private val webClient: WebClient,
												 private val appConfiguration: AppConfiguration) : FileserviceInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FilElementDto> {
		try {
			val fileIds = getFileIds(data)
			logger.info("$key: Getting files with ids: '$fileIds'")

			val files = getFiles(fileIds)

			logger.info("$key: Received files.size: ${files.size}")
			return files

		} catch (e: Exception) {
			logger.error("$key: Error retrieving files from file storage", e)
			throw ArchivingException(e)
		}
	}

	override fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema) {
		val fileIds = getFileIds(data).joinToString(",")
		try {

			logger.info("$key: Calling file storage to delete '$fileIds'")
			deleteFiles(fileIds)
			logger.info("$key: The files: $fileIds are deleted")

		} catch (e: Exception) {
			logger.warn("$key: Failed to delete files from file storage. Everything is saved to Joark correctly, " +
				"so this error will be ignored. Affected file ids: '$fileIds'", e)
		}
	}


	private fun getFiles(fileIds: List<String>): List<FilElementDto> {

		val idChunks = fileIds.chunked(filesInOneRequestToFilestorage).map { it.joinToString(",") }
		val files = idChunks
			.mapNotNull { performGetCall(it) }
			.flatten()

		if (fileIds.size != files.size) {
			val fetchedFiles = files.map { it.uuid }
			throw Exception("Was not able to fetch the files with these ids: ${fileIds.filter { !fetchedFiles.contains(it) }}")
		}
		return files
	}

	private fun deleteFiles(fileIds: String) {
		val webClient = setupWebClient(fileIds, HttpMethod.DELETE)
		webClient.retrieve().bodyToMono(String::class.java).block()
	}

	private fun performGetCall(fileIds: String): List<FilElementDto>? {
		val webClient = setupWebClient(fileIds, HttpMethod.GET)

		return webClient
			.retrieve()
			.bodyToFlux(FilElementDto::class.java)
			.collectList()
			.block() // TODO Do we need to block?
	}

	private fun setupWebClient(fileIds: String, method: HttpMethod): WebClient.RequestBodySpec {
		val uri = appConfiguration.config.filestorageHost + appConfiguration.config.filestorageUrl + fileIds

/*
		val auth = "${appConfiguration.config.username}:${appConfiguration.config.sharedPassword}"
		val encodedAuth: ByteArray = Base64.encodeBase64(auth.toByteArray())
		val authHeader = "Basic " + String(encodedAuth)
*/

		return webClient
			.method(method)
			.uri(uri)
			.contentType(APPLICATION_JSON)
			.accept(APPLICATION_JSON)
			//.header("Authorization", authHeader)
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.getMottatteDokumenter()
			.flatMap { it.getMottatteVarianter().map { variant -> variant.getUuid() } }
}

const val filesInOneRequestToFilestorage = 5
