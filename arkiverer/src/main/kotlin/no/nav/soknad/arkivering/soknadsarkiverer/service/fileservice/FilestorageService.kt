package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FilestorageService(
	private val filesApi: FilesApi,
	private val healthApi: HealthApi,
	private val metrics: ArchivingMetrics
) : FileserviceInterface {

	private val logger = LoggerFactory.getLogger(javaClass)


	override fun ping(): String {
		healthApi.ping()
		return "pong"
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

		} catch (e: ArchivingException ) {
			metrics.incGetFilestorageErrors()
			throw e
		} catch (e: FilesAlreadyDeletedException) {
			metrics.incGetFilestorageErrors()
			throw e
		} catch (e: Exception) {
			metrics.incGetFilestorageErrors()
			throw ArchivingException(e.message ?: "", e)

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


	private fun getFiles(key: String, fileIds: List<String>) = fileIds.map { performGetCall(key, listOf(it)) }.flatten()

	private fun deleteFiles(key: String, fileIds: List<String>) {
		filesApi.deleteFiles(fileIds, key)
	}

	private fun performGetCall(key: String, fileIds: List<String>): List<FileData> {
		val files = filesApi.findFilesByIds(ids = fileIds, xInnsendingId = key, metadataOnly = false)

		if (files.all { it.status == "deleted" })
			throw FilesAlreadyDeletedException("$key: All the files are deleted: $fileIds")
		if (files.any { it.status != "ok" })
			throw ArchivingException("$key: Files had different statuses: ${files.map { "${it.id} - ${it.status}" }}", RuntimeException("$key: Got some, but not all files"))

		return files
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}
