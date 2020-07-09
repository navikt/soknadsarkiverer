package no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi.api

data class OpprettJournalpostRequest(val bruker: Bruker, val datoMottatt: String, val dokumenter: List<Dokument>,
																		 val eksternReferanseId: String, val journalpostType: String, val kanal: String,
																		 val tema: String, val tittel: String)

data class Bruker(val id: String, val idType: String)

data class Dokument(val brevkode: String, val dokumentKategori: String, val dokumentvarianter: List<DokumentVariant>,
										val tittel: String)

data class DokumentVariant(val filnavn: String, val filtype: String, val fysiskDokument: ByteArray, val variantformat: String) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as DokumentVariant

		if (filnavn != other.filnavn) return false
		if (filtype != other.filtype) return false
		if (!fysiskDokument.contentEquals(other.fysiskDokument)) return false
		if (variantformat != other.variantformat) return false

		return true
	}

	override fun hashCode(): Int {
		var result = filnavn.hashCode()
		result = 31 * result + filtype.hashCode()
		result = 31 * result + fysiskDokument.contentHashCode()
		result = 31 * result + variantformat.hashCode()
		return result
	}
}
