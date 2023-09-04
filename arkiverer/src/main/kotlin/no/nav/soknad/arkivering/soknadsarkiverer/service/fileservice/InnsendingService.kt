package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.innsending.api.HealthApi
import no.nav.soknad.innsending.api.HentInnsendteFilerApi
import no.nav.soknad.innsending.model.SoknadFile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class InnsendingService(
	private val innsendingApi: HentInnsendteFilerApi,
	private val healthApi: HealthApi,
	private val metrics: ArchivingMetrics
) : FileserviceInterface  {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun ping(): String {
		healthApi.ping()
		return "pong"
	}

	override fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): FetchFileResponse {
			val timer = metrics.filestorageGetLatencyStart()
			try {
				val fileIds = getFileIds(data)
				logger.info("$key: Getting files from innsending-api with ids: '$fileIds'")

				val fetchFileResponse = getFiles(key, fileIds)

				logger.info("$key: From innsending-api for filids ${fileIds} received status ${fetchFileResponse.status} with ${fetchFileResponse.files?.size} files with a sum of ${fetchFileResponse.files?.sumOf { it.content?.size ?: 0 }} bytes from innsending-api")
				return fetchFileResponse

			} finally {
				metrics.endTimer(timer)
			}
	}

	override fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema) {
	}

	private fun getFiles(key: String, fileIds: List<String>) =
		mergeFetchResponsesAndSetOverallStatus(key, fileIds.map { getOneFile(key, it) } )


	fun mapToFileInfo(soknadFile: SoknadFile?): List<FileInfo>? {
		if (soknadFile == null) return null
		return listOf(FileInfo(uuid=soknadFile.id, content = soknadFile.content, status = mapToResponseStatus(soknadFile.fileStatus)))
	}


	private fun getOneFile(key: String, fileId: String): FetchFileResponse {
		try {
			logger.info("$key: Skal hente fil fra innsending-api $fileId")
			val files = innsendingApi.hentInnsendteFiler(uuids = listOf(fileId), xInnsendingId = key)
			logger.info("$key: Hentet fil fra innsending-api ${files.map{it.fileStatus}.toList()}")

			if (files.size > 1) {
				logger.error("$key: Fetched more than on files for attachment $fileId, Only using the first")
			}

			if (files.all{it.fileStatus == SoknadFile.FileStatus.ok})
				return FetchFileResponse(status = ResponseStatus.Ok.value, files = mapToFileInfo(files.firstOrNull()), exception = null)
			if (files.any{it.fileStatus == SoknadFile.FileStatus.notfound}) {
				return FetchFileResponse(status = ResponseStatus.NotFound.value, files = mapToFileInfo(files.firstOrNull()), exception = null)
			}
			if (files.any{ it.fileStatus == SoknadFile.FileStatus.deleted }) {
				return FetchFileResponse(status = ResponseStatus.Deleted.value, files = null, exception = null)
			}
			return FetchFileResponse(status = ResponseStatus.Error.value, files = null, exception = RuntimeException("$key: Feil ved henting av fil = $fileId"))
		} catch (ex: Exception) {
			logger.error("$key: performGetCall", ex)
			return FetchFileResponse(status = ResponseStatus.Error.value, files = null, exception = ex)
		}
	}

	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}


