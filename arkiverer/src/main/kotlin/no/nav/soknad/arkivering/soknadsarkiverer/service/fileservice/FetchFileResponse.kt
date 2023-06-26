package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

data class FetchFileResponse (
	val status: String,
	val files: List<FileInfo>?,
	val exception: Exception?
)
