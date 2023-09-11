package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.Constants
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.api.HealthApi
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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


	override fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): FetchFileResponse {
		if (filterRequestOnApplicationNumber(data)) { // Avgrenser forsøk på å hente filer fra soknadsfillager til de søknader med hoveddokument med avgrenset sett av skjemanummere
			val timer = metrics.filestorageGetLatencyStart()
			MDC.put(Constants.MDC_INNSENDINGS_ID, key)
			try {
				val fileIds = getFileIds(data)
				logger.info("$key: Getting files with ids: '$fileIds'")

				val fetchFileResponse = getFiles(key, fileIds)

				logger.info("$key: Received ${fetchFileResponse.files?.size} files with a sum of ${fetchFileResponse.files?.sumOf { it.content?.size ?: 0 }} bytes")
				return fetchFileResponse

			} finally {
				metrics.endTimer(timer)
			}
		} else {
			return FetchFileResponse(status = ResponseStatus.NotFound.value, files = null, exception = null )
		}
	}

	private fun filterRequestOnApplicationNumber(data: Soknadarkivschema): Boolean {
		return relevantApplicationNumbers.any{ it == data.mottatteDokumenter.first{it.erHovedskjema }.skjemanummer}
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

	private fun getFiles(key: String, fileIds: List<String>) =
		mergeFetchResponsesAndSetOverallStatus(key, fileIds.map { getOneFile(key, it) } )


	private fun deleteFiles(key: String, fileIds: List<String>) {
		filesApi.deleteFiles(fileIds, key)
	}

	private fun returnFirstOrNull(files: List<FileData>?): List<FileInfo>? {
		if (files == null || files.isEmpty()) return null
		return mapToFileInfo(files.first())
	}

	fun mapToFileInfo(fileData: FileData?): List<FileInfo>? {
		if (fileData == null) return null
		return listOf(FileInfo(uuid=fileData.id, content = fileData.content, status = mapToResponseStatus(fileData.status?: "not-found")))
	}

	private fun getOneFile(key: String, fileId: String?): FetchFileResponse {
		try {
			if (fileId == null) return FetchFileResponse("ok", listOf(), null )
			val files = filesApi.findFilesByIds(ids = listOf(fileId), xInnsendingId = key, metadataOnly = false)

			if (files.size > 1) {
				logger.error("$key: Fetched more than on files for attachment $fileId, Only using the first")
			}
			if (files.all {it.status == ResponseStatus.Ok.value})
				return FetchFileResponse(status = ResponseStatus.Ok.value, files = returnFirstOrNull(files), exception = null)
			if (files.all { it.status == ResponseStatus.NotFound.value })
				return FetchFileResponse(status = ResponseStatus.NotFound.value, files = returnFirstOrNull(files), exception = null)
			if (files.all { it.status == ResponseStatus.Deleted.value })
				return FetchFileResponse(status = ResponseStatus.Deleted.value, files = null, exception = null)
			else
				return FetchFileResponse(status = ResponseStatus.NotFound.value, files = returnFirstOrNull(files), exception = null)
		} catch (ex: Exception) {
			return FetchFileResponse(status = "error", files = null, exception = ex)
		}
	}


	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}
