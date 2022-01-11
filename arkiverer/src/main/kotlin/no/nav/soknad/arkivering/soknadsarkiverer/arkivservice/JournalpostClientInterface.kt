package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto

interface JournalpostClientInterface {

	fun isAlive(): String

	fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FilElementDto>): String
}
