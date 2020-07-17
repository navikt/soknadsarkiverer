package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto

interface FileserviceInterface {

	fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): List<FilElementDto>

	fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema)
}
