package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.converter

import no.nav.soknad.arkivering.avroschemas.MottattDokument
import no.nav.soknad.arkivering.avroschemas.MottattVariant
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.dto.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


fun createOpprettJournalpostRequest(o: Soknadarkivschema, attachedFiles: List<FilElementDto>): OpprettJournalpostRequest {
	val date = DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(o.innsendtDato), ZoneOffset.UTC))

	val documents = createDocuments(o.mottatteDokumenter, attachedFiles, o.soknadstype)
	val tittel = getTitleFromMainDocument(documents)

	return OpprettJournalpostRequest(AvsenderMottaker(o.fodselsnummer, "FNR"), Bruker(o.fodselsnummer, "FNR"),
		date, documents, o.behandlingsid, "INNGAAENDE", "NAV_NO", o.arkivtema, tittel)
}

private fun getTitleFromMainDocument(documents: List<Dokument>): String {
	return documents[0].tittel
}

private fun renameTitleDependingOnSoknadstype(tittel: String, soknadstype: Soknadstyper, erHovedskjema: Boolean): String {
	return if (soknadstype == Soknadstyper.ETTERSENDING && erHovedskjema) {
		"Ettersendelse til " + tittel.replaceFirst(tittel[0], tittel[0].toLowerCase())
	} else {
		tittel
	}
}

private fun createDocuments(mottatteDokumenter: List<MottattDokument>, attachedFiles: List<FilElementDto>,
														soknadstype: Soknadstyper): List<Dokument> {

	val hovedDokument = createHoveddokument(mottatteDokumenter, attachedFiles, soknadstype)
	val vedlegg = createVedlegg(mottatteDokumenter, attachedFiles, soknadstype)

	return arrayOf(arrayOf(hovedDokument), vedlegg.toTypedArray()).flatten()
}

private fun createHoveddokument(documents: List<MottattDokument>, attachedFiles: List<FilElementDto>,
																soknadstype: Soknadstyper): Dokument {
	val hoveddokument = documents
		.filter { it.erHovedskjema }
		.map { createDokument(it, attachedFiles, soknadstype) }

	if (hoveddokument.size != 1)
		throw Exception("Fant ${hoveddokument.size} hoveddokumenter, forventet eksakt 1")
	return hoveddokument[0]
}

private fun createVedlegg(documents: List<MottattDokument>, attachedFiles: List<FilElementDto>,
													soknadstype: Soknadstyper): List<Dokument> {
	return documents
		.filter { !it.erHovedskjema }
		.map { createDokument(it, attachedFiles, soknadstype) }
}

private fun createDokument(document: MottattDokument, attachedFiles: List<FilElementDto>, soknadstype: Soknadstyper): Dokument {
	val dokumentvarianter = document.mottatteVarianter.map { createDokumentVariant(it, attachedFiles) }
	val skjemanummer = getSkjemanummer(document, soknadstype)

	if (skjemanummer.isBlank()) {
		throw Exception("Skjemanummer is not set. This is neccessary inorder to set brevtype on document in archive")
	}

	if (dokumentvarianter.isEmpty())
		throw Exception("Expected there to be at least one DokumentVariant")

	return Dokument(renameTitleDependingOnSoknadstype(document.tittel, soknadstype, document.erHovedskjema),
		skjemanummer, "SOK", dokumentvarianter)
}

private fun getSkjemanummer(document: MottattDokument, soknadstype: Soknadstyper): String {
	return if (soknadstype == Soknadstyper.ETTERSENDING)
		document.skjemanummer.replace("NAV ", "NAVe ")
	else
		document.skjemanummer
}

private fun createDokumentVariant(variant: MottattVariant, attachedFiles: List<FilElementDto>): DokumentVariant {
	val attachedFile = attachedFiles.filter { it.uuid == variant.uuid }

	if (attachedFile.size != 1)
		throw Exception("Found ${attachedFile.size} files matching uuid '${variant.uuid}', expected 1")
	if (attachedFile[0].fil == null)
		throw Exception("File with uuid '${variant.uuid}' was null!")

	return DokumentVariant(variant.filnavn, if (variant.filtype == "PDF/A") "PDFA" else variant.filtype, attachedFile[0].fil!!, variant.variantformat)
}
