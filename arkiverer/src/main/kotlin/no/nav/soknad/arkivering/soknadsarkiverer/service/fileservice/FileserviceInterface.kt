package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg

interface FileserviceInterface {
	fun getFilesFromFilestorage(key: String, data: InnsendingTopicMsg): FetchFileResponse

	fun deleteFilesFromFilestorage(key: String, data: InnsendingTopicMsg)

	fun ping(): String

}
