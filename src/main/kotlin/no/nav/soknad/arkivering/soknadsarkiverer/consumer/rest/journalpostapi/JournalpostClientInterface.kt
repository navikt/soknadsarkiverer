package no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto

interface JournalpostClientInterface {

	fun ping(): String

	fun opprettjournalpost(applicationMessage: Soknadarkivschema, attachedFiles: List<FilElementDto>): String
}
