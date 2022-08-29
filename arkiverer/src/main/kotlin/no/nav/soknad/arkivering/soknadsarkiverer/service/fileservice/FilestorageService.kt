package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FilestorageService(
	fileStorageProperties: FileStorageProperties,
	private val metrics: ArchivingMetrics
) : FileserviceInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val filesApi: FilesApi
	private val healthApi: HealthApi

	init {
		jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = fileStorageProperties.username
		ApiClient.password = fileStorageProperties.password
		filesApi = FilesApi(fileStorageProperties.host)
		healthApi = HealthApi(fileStorageProperties.host)
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
			logger.warn(
				"$key: Failed to delete files from file storage. Everything is saved to Joark correctly, " +
					"so this error will be ignored. Affected file ids: '$fileIds'", e
			)

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
		val files = try {
			filesApi.findFilesByIds(ids = fileIds, xInnsendingId = key, metadataOnly = false)
		} catch (e: Exception) {
			logger.error("$key: Exception when fetching files '$fileIds'", e)
			throw e
		}

		if (files.all { it.status == "deleted" })
			throw Exception(FilesAlreadyDeletedException("$key: All the files are deleted: $fileIds"))
		if (files.any { it.status != "ok" })
			throw Exception("$key: Files had different statuses: ${files.map { "${it.id} - ${it.status}" }}")

		return files
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}

const val filesInOneRequestToFilestorage = 5
