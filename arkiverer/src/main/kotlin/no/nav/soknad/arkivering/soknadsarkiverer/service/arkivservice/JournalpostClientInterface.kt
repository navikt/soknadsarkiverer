package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg

interface JournalpostClientInterface {
	fun opprettJournalpost(key: String, soknadarkivschema: InnsendingTopicMsg, attachedFiles: List<FileInfo>): String
}
