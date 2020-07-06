package no.nav.soknad.arkivering.soknadsarkiverer.fileservice

import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto

interface FileserviceInterface {

	fun getFilesFromFilestorage(fileIds: String): List<FilElementDto>

	fun deleteFilesFromFilestorage(fileIds: String)
}
