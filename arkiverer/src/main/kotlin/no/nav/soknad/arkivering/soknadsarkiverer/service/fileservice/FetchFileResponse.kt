package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.soknadsfillager.model.FileData

data class FetchFileResponse (
	val status: String,
	val files: List<FileData>?,
	val exception: Exception?
)
