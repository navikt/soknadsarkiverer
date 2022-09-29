package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsfillager.model.FileData

interface JournalpostClientInterface {
	fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FileData>): String
}
