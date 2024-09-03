package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema

interface FileserviceInterface {
	fun getFilesFromFilestorage(key: String, data: Soknadarkivschema): FetchFileResponse

	fun deleteFilesFromFilestorage(key: String, data: Soknadarkivschema)

	fun ping(): String

}
