package no.nav.soknad.arkivering.soknadsarkiverer.service.safservice

import no.nav.soknad.arkiverer.saf.generated.hentjournalpostgitteksternreferanseid.Journalpost

interface SafServiceInterface {
	fun hentJournalpostGittInnsendingId(innsendingId: String): Journalpost?
}
