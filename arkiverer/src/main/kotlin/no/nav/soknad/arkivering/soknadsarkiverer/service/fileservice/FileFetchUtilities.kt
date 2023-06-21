package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.innsending.model.SoknadFile

// Merk antar at input listen av files i hver fetchFileresponse inneholder kun en fil
// Resulterende FetchFileResponse inneholder avledet status og list av filene
fun mergeFetchResponsesAndSetOverallStatus(key: String, responses: List<FetchFileResponse>): FetchFileResponse {
	return if (responses.all {it.status == ResponseStatus.Ok.value} )
		FetchFileResponse(status = "ok", files = responses.flatMap { it.files?:listOf() }.toList(), exception = null)
	else if (responses.all{it.status== ResponseStatus.NotFound.value})
		FetchFileResponse(status = ResponseStatus.NotFound.value, files = null, exception = null )
	else if (responses.all{it.status == ResponseStatus.Deleted.value})
		FetchFileResponse(status = ResponseStatus.Deleted.value, files = null, exception = null)
	else if (responses.any{it.status== ResponseStatus.Error.value})
		FetchFileResponse(status = ResponseStatus.Error.value, files = null, exception = responses.map{it.exception}.firstOrNull())
	else if (responses.any { it.status == ResponseStatus.NotFound.value })
		FetchFileResponse(status = ResponseStatus.NotFound.value, files = responses.flatMap { it.files?:listOf() }.toList(),
			exception = RuntimeException("$key: files not found ${responses.filter{it.status==ResponseStatus.NotFound.value }.map{it.files?.first()}.toList()}"))
	else if (responses.any { it.status == ResponseStatus.Deleted.value })
		FetchFileResponse(status = ResponseStatus.Deleted.value, files = responses.flatMap { it.files?:listOf() }.toList(),
			exception = RuntimeException("$key: files not found ${responses.filter{it.status==ResponseStatus.Deleted.value }.map{it.files?.first()?.uuid}.toList()}"))
	else
		FetchFileResponse(status = ResponseStatus.Error.value, files = responses.flatMap { it.files?:listOf() }.toList(),
			exception = RuntimeException("$key: unexpected status mix ${responses.map{it.status + ',' + it.files?.first()?.status}.toList()}"))
}

fun mapToResponseStatus(fileStatus:SoknadFile.FileStatus ): ResponseStatus {
	return when(fileStatus) {
		SoknadFile.FileStatus.ok -> ResponseStatus.Ok
		SoknadFile.FileStatus.notMinusFound -> ResponseStatus.NotFound
		SoknadFile.FileStatus.deleted -> ResponseStatus.Deleted
	}
}

fun mapToResponseStatus(fileStatus: String ): ResponseStatus {
	return when(fileStatus) {
		"ok" -> ResponseStatus.Ok
		"not-found" -> ResponseStatus.NotFound
		"deleted" -> ResponseStatus.Deleted
		"error" -> ResponseStatus.Error
		else -> ResponseStatus.NotFound
	}
}
