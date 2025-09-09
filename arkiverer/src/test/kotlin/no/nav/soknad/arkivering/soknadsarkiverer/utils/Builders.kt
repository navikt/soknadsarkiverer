package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.avroschemas.MottattDokument
import no.nav.soknad.arkivering.avroschemas.MottattVariant
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsmottaker.model.AvsenderDto
import no.nav.soknad.arkivering.soknadsmottaker.model.BrukerDto
import no.nav.soknad.arkivering.soknadsmottaker.model.DokumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import no.nav.soknad.arkivering.soknadsmottaker.model.Variant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class SoknadarkivschemaBuilder {
	private var behandlingsid: String = "behandlingsid"
	private var fodselsnummer: String = "12345687901"
	private var arkivtema: String = "TSO"
	private var innsendtDato: Long = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
	private var soknadstype: Soknadstyper = Soknadstyper.SOKNAD
	private var mottatteDokumenter: MutableList<MottattDokument> = mutableListOf()

	fun withBehandlingsid(behandlingsid: String) = apply { this.behandlingsid = behandlingsid }
	fun withFodselsnummer(fodselsnummer: String) = apply { this.fodselsnummer = fodselsnummer }
	fun withArkivtema(arkivtema: String) = apply { this.arkivtema = arkivtema }
	fun withInnsendtDato(innsendtDato: LocalDateTime) = apply { this.innsendtDato = innsendtDato.toEpochSecond(ZoneOffset.UTC) }
	fun withSoknadstype(soknadstype: Soknadstyper) = apply { this.soknadstype = soknadstype }
	fun withMottatteDokumenter(vararg mottatteDokumenter: MottattDokument) = apply { this.mottatteDokumenter.addAll(mottatteDokumenter) }

	fun build() = Soknadarkivschema(behandlingsid, fodselsnummer, arkivtema, innsendtDato, soknadstype, mottatteDokumenter)
}

class InnsendingTopicMsgBuilder {
	private var innsendtDato: OffsetDateTime = OffsetDateTime.now()
	private var innlogget: Boolean = true
	private var innsendingsId: String = UUID.randomUUID().toString()
	private var ettersendelseTilId: String? = null
	private var avsenderId: String? = "12345678901"
	private var avsenderIdType: String? = "FNR"
	private var avsenderNavn: String? = "<NAME>"
	private var brukerId: String? = "12345678901"
	private var brukerIdType: String? = "FNR"
	private var kanal: String = "NAV_NO_UINNLOGGET"
	private var skjemanr: String = "NAV 11-12.10"
	private var tittel: String = "Kjøreliste for godkjent bruk av egen bil"
	private var arkivtema: String = "TSO"
	private var dokumenter: MutableList<DokumentData> = mutableListOf(
		DokumentData(
			skjemanummer = skjemanr,
			erHovedskjema = true,
			tittel = tittel,
			varianter = listOf(
				Variant(
					uuid = UUID.randomUUID().toString(),
					mediaType = "application/pdf",
					filnavn = "filnavn.pdf",
					filtype = "PDFA",
					variantFormat = "ARKIV"
				),
				Variant(
					uuid = UUID.randomUUID().toString(),
					mediaType = "application/json",
					filnavn = "filnavn.json",
					filtype = "PDFA",
					variantFormat = "ORIGINAL"
				)
			)
		),
		DokumentData(
			skjemanummer = "N7",
			erHovedskjema = true,
			tittel = "Kvittering",
			varianter = listOf(
				Variant(
					uuid = UUID.randomUUID().toString(),
					mediaType = "application/pdf",
					filnavn = "n7.pdf",
					filtype = "PDFA",
					variantFormat = "ARKIV"
				),
			)
		)
	)

	fun withInnsendtDato(innsendtDato: OffsetDateTime) = apply { this.innsendtDato = innsendtDato }
	fun withInnlogget(innlogget: Boolean) = apply { this.innlogget = innlogget }
	fun withInnsendingsId(innsendingsId: String) = apply { this.innsendingsId = innsendingsId }
	fun withEttersendelseTilId(ettersendelseTilId: String?) = apply { this.ettersendelseTilId = ettersendelseTilId }
	fun withAvsenderId(avsenderId: String?) = apply { this.avsenderId = avsenderId }
	fun withAvsenderIdType(avsenderIdType: String?) = apply { this.avsenderIdType = avsenderIdType }
	fun withAvsenderNavn(avsenderNavn: String?) = apply { this.avsenderNavn = avsenderNavn }
	fun withBrukerId(brukerId: String?) = apply { this.brukerId = brukerId }
	fun withBrukerIdType(brukerIdType: String?) = apply { this.brukerIdType = brukerIdType }
	fun withKanal(kanal: String) = apply { this.kanal = kanal }
	fun withSkjemanr(skjemanr: String) = apply { this.skjemanr = skjemanr }
	fun withTittel(tittel: String) = apply { this.tittel = tittel }
	fun withArkivtema(arkivtema: String) = apply { this.arkivtema = arkivtema }
	fun withDokumenter(dokumenter: List<DokumentData>) = apply { this.dokumenter.addAll(dokumenter) }
	fun withTestDokumenter(testDokumenter: MutableList<TestDokument>) = apply { this.dokumenter = TestDokumentBuilder().withTestDokumenter(testDokumenter).build().toMutableList() }

	fun build() = InnsendingTopicMsg(
		versjon = "1.0.0",
		innsendingsId = innsendingsId,
		innsendtDato = innsendtDato,
		innlogget =	 innlogget,
		ettersendelseTilId = ettersendelseTilId,
		avsenderDto = AvsenderDto(id=avsenderId, idType= if (avsenderIdType != null) AvsenderDto.IdType.valueOf(avsenderIdType!!) else null, navn = avsenderNavn),
		brukerDto = if (brukerId != null && brukerIdType != null) BrukerDto(
			id = brukerId!!,
			idType = BrukerDto.IdType.valueOf(brukerIdType!!)
		) else null,
		kanal = kanal,
		skjemanr = skjemanr,
		tittel = tittel,
		arkivtema = arkivtema,
		dokumenter = dokumenter
	)
}

data class TestDokument(
	val skjemanummer: String,
	val erHovedskjema: Boolean,
	val tittel: String,
	val uuids: List<String>
)

class TestDokumentBuilder {
	private var testDokumenter: MutableList<TestDokument> = mutableListOf (
		TestDokument(
			skjemanummer = "NAV 11-12.10",
			erHovedskjema = true,
			tittel = "Kjøreliste for godkjent bruk av egen bil",
			uuids = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		)
	)

	fun withTestDokumenter(testDokumenter: MutableList<TestDokument>) = apply { this.testDokumenter = testDokumenter }

	fun build() = testDokumenter.map { dokument ->
		DokumentData(
			skjemanummer = dokument.skjemanummer, tittel = dokument.tittel, erHovedskjema = dokument.erHovedskjema,
			varianter = dokument.uuids.mapIndexed { index: Int, uuid ->
				Variant(
					uuid = uuid,
					mediaType = if (!dokument.erHovedskjema) "application/pdf" else if (index == 0) "application/pdf" else "application/json",
					filnavn = dokument.skjemanummer,
					filtype = if (!dokument.erHovedskjema) "PDFA" else if (index == 0) "PDFA" else "JSON",
					variantFormat = if (index == 0) "ARKIV" else "ORIGINAL"
				)
			}
		)
	}

}

class MottattDokumentBuilder {
	private var skjemanummer: String = "NAV 11-12.10"
	private var erHovedskjema: Boolean = true
	private var tittel: String = "Kjøreliste for godkjent bruk av egen bil"
	private var mottatteVarianter: MutableList<MottattVariant> = mutableListOf()

	fun withSkjemanummer(skjemanummer: String) = apply { this.skjemanummer = skjemanummer }
	fun withErHovedskjema(erHovedskjema: Boolean) = apply { this.erHovedskjema = erHovedskjema }
	fun withTittel(tittel: String) = apply { this.tittel = tittel }
	fun withMottatteVarianter(vararg mottatteVarianter: MottattVariant) = apply { withMottatteVarianter(mottatteVarianter.toList()) }
	fun withMottatteVarianter(mottatteVarianter: List<MottattVariant>) = apply { this.mottatteVarianter.addAll(mottatteVarianter) }

	fun build() = MottattDokument(skjemanummer, erHovedskjema, tittel, mottatteVarianter)
}

class MottattVariantBuilder {
	private var uuid: String = "uuid"
	private var filnavn: String = "filnavn"
	private var filtype: String = "PDFA"
	private var variantformat: String = "ARKIV"

	fun withUuid(uuid: String) = apply { this.uuid = uuid }
	fun withFilnavn(filnavn: String) = apply { this.filnavn = filnavn }
	fun withfiltype(filtype: String) = apply { this.filtype = filtype }
	fun withVariantformat(variantformat: String) = apply { this.variantformat = variantformat }

	fun build() = MottattVariant(uuid, filnavn, filtype, variantformat)
}

fun createSoknadarkivschema(fileId: String = UUID.randomUUID().toString(), behandlingsId: String = UUID.randomUUID().toString()) = createSoknadarkivschema(listOf(fileId), behandlingsId)
fun createSoknadarkivschema(behandlingsId: String = UUID.randomUUID().toString(), tema: String, fileIds: List<String> = listOf(UUID.randomUUID().toString()), ) = createSoknadarkivschema(fileIds, behandlingsId, tema)

fun createSoknadarkivschema(fileIds: List<String>, variantformat: String = "ARKIV",behandlingsId: String = UUID.randomUUID().toString(), tema: String = "AAP") =
	SoknadarkivschemaBuilder()
		.withBehandlingsid(behandlingsId)
		.withArkivtema(tema)
		.withMottatteDokumenter(MottattDokumentBuilder()
			.withMottatteVarianter(
				fileIds.map { MottattVariantBuilder().withUuid(it).withVariantformat(variantformat).build() }
			)
			.build())
		.build()
