package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto

interface JournalpostClientInterface {

	fun ping(): String

	fun opprettjournalpost(key: String, applicationMessage: Soknadarkivschema, attachedFiles: List<FilElementDto>): String
}
