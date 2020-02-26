package no.nav.soknad.arkivering.soknadsarkiverer.dto

import no.nav.soknad.soknadarkivering.avroschemas.MottattDokument
import java.time.LocalDateTime

data class JoarkData(val eksternReferanseId: String, val personId: String, val idType: String, val tema: String,
										 val innsendtDato: LocalDateTime, val mottatteDokumenter: List<MottattDokument>,
										 val attachedFiles: List<FilElementDto>)

data class MottattDokumentDto(var skjemaNummer: String, var erHovedSkjema: Boolean?,
															var tittel: String?, val varianter: List<MottattVariantDto>)

data class MottattVariantDto(val uuid: String, val filNavn: String?, val filtype: String, val variantformat: String)
