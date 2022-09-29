package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsfillager.model.FileData

interface FileserviceInterface {
	fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FileData>

	fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema)

	fun ping(): String
}
