package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

data class FileInfo(
	val uuid: String,
	val content: ByteArray?,
	val status: ResponseStatus = ResponseStatus.Ok
)
