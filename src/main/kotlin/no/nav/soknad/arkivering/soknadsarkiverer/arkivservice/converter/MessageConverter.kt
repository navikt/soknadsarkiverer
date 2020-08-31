package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.converter

import no.nav.soknad.arkivering.avroschemas.MottattDokument
import no.nav.soknad.arkivering.avroschemas.MottattVariant
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.Bruker
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.Dokument
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.DokumentVariant
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.dto.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


fun createOpprettJournalpostRequest(o: Soknadarkivschema, attachedFiles: List<FilElementDto>): OpprettJournalpostRequest {
	val soknadstype = o.getSoknadstype()
	val date = DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(o.getInnsendtDato()), ZoneOffset.UTC))

	val documents = createDocuments(o.getMottatteDokumenter(), attachedFiles, soknadstype)
	val tittel = createTitle(documents, soknadstype)

	return OpprettJournalpostRequest(Bruker(o.getFodselsnummer(), "FNR"), date, documents, o.getBehandlingsid(),
		"INNGAAENDE", "NAV_NO", o.getArkivtema(), tittel)
}

private fun createTitle(documents: List<Dokument>, soknadstype: Soknadstyper): String {
	return if (soknadstype == Soknadstyper.ETTERSENDING) {
		"Ettersendelse til " + documents[0].tittel
	} else {
		"Søknad til " + documents[0].tittel
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
		.filter { it.getErHovedskjema() }
		.map { createDokument(it, attachedFiles, soknadstype) }

	if (hoveddokument.size != 1)
		throw Exception("Fant ${hoveddokument.size} hoveddokumenter, forventet eksakt 1")
	return hoveddokument[0]
}

private fun createVedlegg(documents: List<MottattDokument>, attachedFiles: List<FilElementDto>,
													soknadstype: Soknadstyper): List<Dokument> {
	return documents
		.filter { !it.getErHovedskjema() }
		.map { createDokument(it, attachedFiles, soknadstype) }
}

private fun createDokument(document: MottattDokument, attachedFiles: List<FilElementDto>, soknadstype: Soknadstyper): Dokument {
	val dokumentvarianter = document.getMottatteVarianter().map { createDokumentVariant(it, attachedFiles) }
	val skjemanummer = getSkjemanummer(document, soknadstype)

	if (dokumentvarianter.isEmpty())
		throw Exception("Expected there to be at least one DokumentVariant")

	return Dokument(skjemanummer, "SOK", dokumentvarianter, document.getTittel())
}

private fun getSkjemanummer(document: MottattDokument, soknadstype: Soknadstyper): String {
	return if (soknadstype == Soknadstyper.ETTERSENDING)
		document.getSkjemanummer().replace("NAV ", "NAVe ")
	else
		document.getSkjemanummer()
}

private fun createDokumentVariant(variant: MottattVariant, attachedFiles: List<FilElementDto>): DokumentVariant {
	val attachedFile = attachedFiles.filter { it.uuid == variant.getUuid() }

	if (attachedFile.size != 1)
		throw Exception("Found ${attachedFile.size} files matching uuid '${variant.getUuid()}', expected 1")
	if (attachedFile[0].fil == null)
		throw Exception("File with uuid '${variant.getUuid()}' was null!")

	return DokumentVariant(variant.getFilnavn(), variant.getFiltype(), attachedFile[0].fil!!, variant.getVariantformat())
}
