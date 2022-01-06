package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api

data class OpprettJournalpostResponse(
	val dokumenter: List<Dokumenter>,
	val journalpostId: String,
	val journalpostferdigstilt: Boolean,
	val journalstatus: String? = null,
	val melding: String? = null)

data class Dokumenter(
	val brevkode: String? = null,
	val dokumentInfoId: String? = null,
	val tittel: String? = null)
