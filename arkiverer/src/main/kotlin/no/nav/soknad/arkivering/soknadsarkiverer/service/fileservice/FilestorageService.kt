package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ClientException
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class FilestorageService(
	appConfiguration: AppConfiguration,
	private val metrics: ArchivingMetrics
) : FileserviceInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val filesApi: FilesApi
	private val healthApi: HealthApi

	init {
		jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = appConfiguration.config.username
		ApiClient.password = appConfiguration.config.password
		filesApi = FilesApi(appConfiguration.config.filestorageHost)
		healthApi = HealthApi(appConfiguration.config.filestorageHost)
	}

	override fun ping(): String {
		healthApi.ping()
		return "pong"
	}
	override fun isReady(): String {
		healthApi.isReady()
		return "ok"
	}


	override fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FileData> {
		val timer = metrics.filestorageGetLatencyStart()
		try {
			val fileIds = getFileIds(data)
			logger.info("$key: Getting files with ids: '$fileIds'")

			val files = getFiles(key, fileIds)

			logger.info("$key: Received ${files.size} files with a sum of ${files.sumOf { it.content?.size ?: 0 }} bytes")
			metrics.incGetFilestorageSuccesses()
			return files

		} catch (e: Exception) {
			metrics.incGetFilestorageErrors()
			if (e.cause is FilesAlreadyDeletedException) {
				logger.warn("$key: File(s) were deleted in the file storage when trying to fetch them: ${e.message}")
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

		val fileIds = getFileIds(data)
		try {

			logger.info("$key: Calling file storage to delete $fileIds")
			deleteFiles(key, fileIds)
			logger.info("$key: Deleted these files: $fileIds")

			metrics.incDelFilestorageSuccesses()
		} catch (e: Exception) {
			metrics.incDelFilestorageErrors()
			logger.warn("$key: Failed to delete files from file storage. Everything is saved to Joark correctly, " +
				"so this error will be ignored. Affected file ids: '$fileIds'", e)

		} finally {
			metrics.endTimer(timer)
		}
	}


	private fun getFiles(key: String, fileIds: List<String>): List<FileData> {

		val idChunks = fileIds
			.chunked(filesInOneRequestToFilestorage)

		return idChunks
			.map { performGetCall(key, it) }
			.flatten()
	}

	private fun deleteFiles(key: String, fileIds: List<String>) {
		filesApi.deleteFiles(fileIds, key)
	}

	private fun performGetCall(key: String, fileIds: List<String>): List<FileData> {
		return try {
			filesApi.findFilesByIds(fileIds, key)

		} catch (e: ClientException) {
			val errorMsg = "$key: Got status ${e.statusCode} when requesting files '$fileIds' - response: '${e.response}'"

			if (e.statusCode == HttpStatus.GONE.value())
				throw Exception(FilesAlreadyDeletedException(errorMsg))
			else {
				logger.error(errorMsg, e)
				throw e
			}
		}
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}

const val filesInOneRequestToFilestorage = 5
