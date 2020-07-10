package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto

interface FileserviceInterface {

	fun getFilesFromFilestorage(key: String, fileIds: String): List<FilElementDto>

	fun deleteFilesFromFilestorage(key: String, fileIds: String)
}
