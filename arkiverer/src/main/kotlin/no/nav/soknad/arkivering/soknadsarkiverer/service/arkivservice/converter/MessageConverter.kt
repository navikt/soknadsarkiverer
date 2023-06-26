package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter

import no.nav.soknad.arkivering.avroschemas.MottattDokument
import no.nav.soknad.arkivering.avroschemas.MottattVariant
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId


fun createOpprettJournalpostRequest(o: Soknadarkivschema, attachedFiles: List<FileInfo>): OpprettJournalpostRequest {
	val timestamp = OffsetDateTime.ofInstant(Instant.ofEpochSecond(o.innsendtDato), ZoneId.of("Europe/Oslo")).toString()

	val documents = createDocuments(o.mottatteDokumenter, attachedFiles, o.soknadstype)
	val tittel = getTitleFromMainDocument(documents)

	return OpprettJournalpostRequest(AvsenderMottaker(o.fodselsnummer, "FNR"), Bruker(o.fodselsnummer, "FNR"),
		timestamp, documents, o.behandlingsid, "INNGAAENDE", "NAV_NO", o.arkivtema, tittel)
}

private fun getTitleFromMainDocument(documents: List<Dokument>): String {
	return documents[0].tittel
}

private fun renameTitleDependingOnSoknadstype(tittel: String, soknadstype: Soknadstyper, erHovedskjema: Boolean): String {
	return if (soknadstype == Soknadstyper.ETTERSENDING && erHovedskjema) {
		"Ettersendelse til " + tittel.replaceFirst(tittel[0], tittel[0].lowercaseChar())
	} else {
		tittel
	}
}

private fun createDocuments(mottatteDokumenter: List<MottattDokument>, attachedFiles: List<FileInfo>,
														soknadstype: Soknadstyper): List<Dokument> {

	val hovedDokument = createHoveddokument(mottatteDokumenter, attachedFiles, soknadstype)
	val vedlegg = createVedlegg(mottatteDokumenter, attachedFiles, soknadstype)

	return arrayOf(arrayOf(hovedDokument), vedlegg.toTypedArray()).flatten()
}

private fun createHoveddokument(documents: List<MottattDokument>, attachedFiles: List<FileInfo>,
																soknadstype: Soknadstyper): Dokument {
	val hoveddokument = documents
		.filter { it.erHovedskjema }
		.map { createDokument(it, attachedFiles, soknadstype) }

	if (hoveddokument.size != 1)
		throw Exception("Fant ${hoveddokument.size} hoveddokumenter, forventet eksakt 1")
	return hoveddokument[0]
}

private fun createVedlegg(documents: List<MottattDokument>, attachedFiles: List<FileInfo>,
													soknadstype: Soknadstyper): List<Dokument> {
	return documents
		.filter { !it.erHovedskjema }
		.map { createDokument(it, attachedFiles, soknadstype) }
}

private fun createDokument(document: MottattDokument, attachedFiles: List<FileInfo>, soknadstype: Soknadstyper): Dokument {
	val dokumentvarianter = filterDuplicates(document.mottatteVarianter.map { createDokumentVariant(it, attachedFiles) })

	val skjemanummer = getSkjemanummer(document, soknadstype)

	if (skjemanummer.isBlank()) {
		throw Exception("Skjemanummer is not set. This is neccessary inorder to set brevtype on document in archive")
	}

	if (dokumentvarianter.isEmpty())
		throw Exception("Expected there to be at least one DokumentVariant")

	return Dokument(renameTitleDependingOnSoknadstype(document.tittel, soknadstype, document.erHovedskjema),
		skjemanummer, "SOK", dokumentvarianter)
}

private fun filterDuplicates(dokumentVarianter: List<DokumentVariant>): List<DokumentVariant> {
	if (dokumentVarianter.size <= 1) return dokumentVarianter
	return dokumentVarianter.distinctBy { it.variantformat }
}

private fun getSkjemanummer(document: MottattDokument, soknadstype: Soknadstyper): String {
	return if (soknadstype == Soknadstyper.ETTERSENDING)
		document.skjemanummer.replace("NAV ", "NAVe ")
	else
		document.skjemanummer
}

private fun createDokumentVariant(variant: MottattVariant, attachedFiles: List<FileInfo>): DokumentVariant {
	val attachedFile = attachedFiles.filter { it.uuid == variant.uuid }

	if (attachedFile.size != 1)
		throw Exception("Found ${attachedFile.size} files matching uuid '${variant.uuid}', expected 1")
	if (attachedFile[0].content == null)
		throw Exception("File with uuid '${variant.uuid}' was null!")

	val filtype = if (variant.filtype.equals("PDF/A",true) ) "PDFA" else variant.filtype.uppercase()
	return DokumentVariant(variant.filnavn, filtype, attachedFile[0].content!!, variant.variantformat)
}
