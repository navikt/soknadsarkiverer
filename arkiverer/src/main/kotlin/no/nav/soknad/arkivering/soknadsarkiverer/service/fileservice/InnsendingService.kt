package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
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
			logger.info("$key: Getting files with ids: '$fileIds'")

			val fetchFileResponse = getFiles(key, fileIds)

			logger.info("$key: Received ${fetchFileResponse.files?.size} files with a sum of ${fetchFileResponse.files?.sumOf { it.content?.size ?: 0 }} bytes")
			return fetchFileResponse

		} finally {
			metrics.endTimer(timer)
		}
	}

	override fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema) {
	}

	private fun getFiles(key: String, fileIds: List<String>) =
		mergeFetchResponses(fileIds.map { performGetCall(key, listOf(it)) } )

	private fun mergeFetchResponses(responses: List<FetchFileResponse>): FetchFileResponse {
		return if (responses.any{it.status== "error"})
			FetchFileResponse(status = "error", files = null, exception = responses.map{it.exception}.firstOrNull())
		else if (responses.all{it.status == "deleted"})
			FetchFileResponse(status = "deleted", files = null, exception = null)
		else if (responses.any { it.status != "ok" })
			FetchFileResponse(status = "not-found", files = responses.flatMap { it.files?:listOf() }.toList(), exception = null)
		else
			FetchFileResponse(status = "ok", files = responses.flatMap { it.files?:listOf() }.toList(), exception = null)
	}

	private fun mapToFileData(soknadFiles: List<SoknadFile>):List<FileData> {
		return soknadFiles.stream().map{FileData(id=it.id, content = it.content, createdAt = it.createdAt, status = it.status )}.toList()
	}

	private fun performGetCall(key: String, fileIds: List<String>): FetchFileResponse {
		try {
			val files = innsendingApi.hentInnsendteFiler(uuid = fileIds, xInnsendingId = key)

			if (files.all { it.status == "deleted" })
				return FetchFileResponse(status = "deleted", files = null, exception = null)
			if (files.any { it.status != "ok" })
				return FetchFileResponse(status = "not-found", files = mapToFileData(files), exception = null)
			/*
							throw ArchivingException(
								"$key: Files had different statuses: ${files.map { "${it.id} - ${it.status}" }}",
								RuntimeException("$key: Got some, but not all files")
							)
			*/
			else return FetchFileResponse(status = "ok", files = mapToFileData(files), exception = null)
		} catch (ex: Exception) {
			return FetchFileResponse(status = "error", files = null, exception = ex)
		}
	}

	private fun getFileIds(data: Soknadarkivschema) =
		data.mottatteDokumenter
			.flatMap { it.mottatteVarianter.map { variant -> variant.uuid } }
}


