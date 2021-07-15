package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.apache.tomcat.util.codec.binary.Base64
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class FilestorageService(@Qualifier("basicWebClient") private val webClient: WebClient,
												 private val appConfiguration: AppConfiguration,
												 private val metrics: ArchivingMetrics
) : FileserviceInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun ping() = healthCheck(appConfiguration.config.filestorageHost + "/internal/ping")
	override fun isReady() = healthCheck(appConfiguration.config.filestorageHost + "/internal/isReady")
	private fun healthCheck(uri: String) = webClient
		.get()
		.uri(uri)
		.retrieve()
		.bodyToMono(String::class.java)
		.block()!!


	override fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FilElementDto> {
		val timer = metrics.filestorageGetLatencyStart()
		try {
			val fileIds = getFileIds(data)
			logger.info("$key: Getting files with ids: '$fileIds'")

			val files = getFiles(fileIds)

			logger.info("$key: Received ${files.size} files with a sum of ${files.sumOf { it.fil?.size ?: 0 }} bytes")
			metrics.incGetFilestorageSuccesses()
			return files

		} catch (e: Exception) {
			metrics.incGetFilestorageErrors()
			if (e.cause is FilesAlreadyDeletedException) {
				logger.warn("$key: File(s) deleted in the file storage", e)
				throw e
			} else {
				logger.error("$key: Error retrieving files from the file storage", e)
				throw ArchivingException(e)
			}

		} finally {
			metrics.endTimer(timer)
		}
	}

	override fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema) {
		val timer = metrics.filestorageDelLatencyStart()

		val fileIds = getFileIds(data).joinToString(",")
		try {

			logger.info("$key: Calling file storage to delete '$fileIds'")
			deleteFiles(fileIds)
			logger.info("$key: The files: $fileIds are deleted")

			metrics.incDelFilestorageSuccesses()
		} catch (e: Exception) {
			metrics.incDelFilestorageErrors()
			logger.warn("$key: Failed to delete files from file storage. Everything is saved to Joark correctly, " +
				"so this error will be ignored. Affected file ids: '$fileIds'", e)

		} finally {
			metrics.endTimer(timer)
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
		val uri = appConfiguration.config.filestorageHost + appConfiguration.config.filestorageUrl + fileIds
		val method = HttpMethod.DELETE
		val webClient = setupWebClient(uri, method)

		webClient
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response -> response.bodyToMono(String::class.java).map { Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'") } })
			.bodyToMono(String::class.java)
			.block() // TODO Do we need to block?
	}

	private fun performGetCall(fileIds: String): List<FilElementDto>? {
		val uri = appConfiguration.config.filestorageHost + appConfiguration.config.filestorageUrl + fileIds
		val method = HttpMethod.GET
		val webClient = setupWebClient(uri, method)

		return webClient
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response -> response.bodyToMono(String::class.java).map {
						if (response.statusCode() == HttpStatus.GONE) {
							FilesAlreadyDeletedException("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'")
						} else {
							Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'")
						}
					}
				}
			)
			.bodyToFlux(FilElementDto::class.java)
			.collectList()
			.block() // TODO Do we need to block?
	}

	private fun setupWebClient(uri: String, method: HttpMethod): WebClient.RequestBodySpec {

		val auth = "${appConfiguration.config.username}:${appConfiguration.config.sharedPassword}"
		val encodedAuth: ByteArray = Base64.encodeBase64(auth.toByteArray())
		val authHeader = "Basic " + String(encodedAuth)

		return webClient
			.method(method)
			.uri(uri)
			.contentType(APPLICATION_JSON)
			.accept(APPLICATION_JSON)
			.header("Authorization", authHeader)
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}

const val filesInOneRequestToFilestorage = 5
