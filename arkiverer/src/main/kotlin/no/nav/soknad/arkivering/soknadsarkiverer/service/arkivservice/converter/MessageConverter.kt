package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter

import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsmottaker.model.DokumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import no.nav.soknad.arkivering.soknadsmottaker.model.Variant

fun createOpprettJournalpostRequest(o: InnsendingTopicMsg, attachedFiles: List<FileInfo>): OpprettJournalpostRequest {
	val timestamp = o.innsendtDato.toString()

	val documents = createDocuments(o.dokumenter, attachedFiles, o.ettersendelseTilId)
	val tittel = getTitleFromMainDocument(documents)

	val bruker: Bruker? = if (o.brukerDto == null) null else Bruker(id = o.brukerDto!!.id, idType = o.brukerDto!!.idType.name)
	return OpprettJournalpostRequest(
		avsenderMottaker = AvsenderMottaker(id = o.avsenderDto.id, idType = o.avsenderDto.idType?.name, navn = o.avsenderDto.navn),
		bruker = bruker,
		timestamp, documents, o.innsendingsId, "INNGAAENDE", o.kanal, o.arkivtema, tittel)
}

private fun getTitleFromMainDocument(documents: List<Dokument>): String {
	return documents[0].tittel
}

private fun renameTitleDependingOnSoknadstype(tittel: String, ettersendingIdRef: String?, erHovedskjema: Boolean): String {
	return if (ettersendingIdRef != null && erHovedskjema) {
		"Ettersendelse til " + tittel.replaceFirst(tittel[0], tittel[0].lowercaseChar())
	} else {
		tittel
	}
}

private fun createDocuments(mottatteDokumenter: List<DokumentData>, attachedFiles: List<FileInfo>,
														ettersendelseTilId: String?): List<Dokument> {

	val hovedDokument = createHoveddokument(mottatteDokumenter, attachedFiles, ettersendelseTilId)
	val vedlegg = createVedlegg(mottatteDokumenter, attachedFiles, ettersendelseTilId)

	return arrayOf(arrayOf(hovedDokument), vedlegg.toTypedArray()).flatten()
}

private fun createHoveddokument(documents: List<DokumentData>, attachedFiles: List<FileInfo>,
																ettersendingIdRef: String?): Dokument {
	val hoveddokument = documents
		.filter { it.erHovedskjema }
		.map { createDokument(it, attachedFiles, ettersendingIdRef) }

	if (hoveddokument.size != 1)
		throw Exception("Fant ${hoveddokument.size} hoveddokumenter, forventet eksakt 1")
	return hoveddokument[0]
}

private fun createVedlegg(documents: List<DokumentData>, attachedFiles: List<FileInfo>,
													ettersendelseTilId: String?): List<Dokument> {
	return documents
		.filter { !it.erHovedskjema }
		.map { createDokument(it, attachedFiles, ettersendelseTilId) }
}

private fun createDokument(document: DokumentData, attachedFiles: List<FileInfo>, ettersendelseTilId: String?): Dokument {
	val dokumentvarianter = filterDuplicates(document.varianter.map { createDokumentVariant(it, attachedFiles) })

	val skjemanummer = getSkjemanummer(document, ettersendelseTilId)

	if (skjemanummer.isBlank()) {
		throw Exception("Skjemanummer is not set. This is neccessary inorder to set brevtype on document in archive")
	}

	if (dokumentvarianter.isEmpty())
		throw Exception("Expected there to be at least one DokumentVariant")

	return Dokument(renameTitleDependingOnSoknadstype(document.tittel, ettersendelseTilId, document.erHovedskjema),
		skjemanummer, "SOK", dokumentvarianter)
}

private fun filterDuplicates(dokumentVarianter: List<DokumentVariant>): List<DokumentVariant> {
	if (dokumentVarianter.size <= 1) return dokumentVarianter
	return dokumentVarianter.distinctBy { it.variantformat }
}

private fun getSkjemanummer(document: DokumentData, ettersendelseTilId: String?): String {
	return if (ettersendelseTilId != null)
		document.skjemanummer.replace("NAV ", "NAVe ")
	else
		document.skjemanummer
}

private fun createDokumentVariant(variant: Variant, attachedFiles: List<FileInfo>): DokumentVariant {
	val attachedFile = attachedFiles.filter { it.uuid == variant.uuid }

	if (attachedFile.size != 1)
		throw Exception("Found ${attachedFile.size} files matching uuid '${variant.uuid }', expected 1")
	if (attachedFile[0].content == null)
		throw Exception("File with uuid '${variant.uuid}' was null!")

	val filtype = if (variant.filtype.equals("PDF/A",true) ) "PDFA" else variant.filtype.uppercase()
	return DokumentVariant(
		filnavn = variant.filnavn,
		filtype = filtype,
		fysiskDokument = attachedFile[0].content!!,
		variantformat = variant.variantFormat ?: "ARKIV")
}
