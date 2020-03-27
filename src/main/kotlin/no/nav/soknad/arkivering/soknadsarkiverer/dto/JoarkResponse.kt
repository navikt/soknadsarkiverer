package no.nav.soknad.arkivering.soknadsarkiverer.dto

data class JoarkResponse(val dokumenter: List<Dokumenter>, val journalpostId: String,
												 val journalpostferdigstilt: Boolean, val journalstatus: String, val melding: String)

data class Dokumenter(val brevkode: String, val dokumentInfoId: String, val tittel: String)
