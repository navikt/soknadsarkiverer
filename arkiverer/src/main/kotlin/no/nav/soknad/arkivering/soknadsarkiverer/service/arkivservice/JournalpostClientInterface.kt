package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo

interface JournalpostClientInterface {
	fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FileInfo>): String
}
