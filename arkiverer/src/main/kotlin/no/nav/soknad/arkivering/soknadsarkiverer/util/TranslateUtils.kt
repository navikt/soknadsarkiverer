package no.nav.soknad.arkivering.soknadsarkiverer.util

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsmottaker.model.AvsenderDto
import no.nav.soknad.arkivering.soknadsmottaker.model.BrukerDto
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId


fun translate(soknadarkivschema: Soknadarkivschema): InnsendingTopicMsg {
	return InnsendingTopicMsg (

		innsendtDato = translate(soknadarkivschema.innsendtDato),
		innlogget = true,
		innsendingsId = soknadarkivschema.behandlingsid,
		ettersendelseTilId = if (soknadarkivschema.soknadstype == Soknadstyper.SOKNAD) null else soknadarkivschema.behandlingsid,
		avsenderDto = AvsenderDto(id = soknadarkivschema.fodselsnummer, idType = AvsenderDto.IdType.FNR),
		brukerDto = BrukerDto(id = soknadarkivschema.fodselsnummer, idType = BrukerDto.IdType.FNR),
		kanal = "NAV_NO",
		skjemanr = soknadarkivschema.mottatteDokumenter.first().skjemanummer,
		tittel = soknadarkivschema.mottatteDokumenter.first().skjemanummer,
		arkivtema = soknadarkivschema.arkivtema,
		dokumenter = soknadarkivschema.mottatteDokumenter.map { document ->
			no.nav.soknad.arkivering.soknadsmottaker.model.DokumentData(
				skjemanummer = document.skjemanummer,
				erHovedskjema = document.erHovedskjema,
				tittel = document.tittel,
				varianter = document.mottatteVarianter.map { variant ->
					no.nav.soknad.arkivering.soknadsmottaker.model.Variant (
						uuid = variant.uuid,
						mediaType = variant.variantformat,
						filnavn = variant.filnavn,
						filtype = variant.filtype,
						variantFormat = variant.variantformat ?: "ARKIV"
					)
				}
			)
		}
		)
}

fun translate(time: Long): OffsetDateTime {
	return OffsetDateTime.ofInstant(Instant.ofEpochSecond(time), ZoneId.of("Europe/Oslo"))
}
