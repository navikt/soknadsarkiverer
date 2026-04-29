package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsmottaker.model.AvsenderDto
import no.nav.soknad.arkivering.soknadsmottaker.model.BrukerDto
import no.nav.soknad.arkivering.soknadsmottaker.model.DokumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import no.nav.soknad.arkivering.soknadsmottaker.model.Variant
import java.time.OffsetDateTime
import java.util.*

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
					filtype = "json",
					variantFormat = "ORIGINAL"
				)
			)
		),
		DokumentData(
			skjemanummer = "L7",
			erHovedskjema = false,
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

fun createInnsendingTopicMsg(fileId: String = UUID.randomUUID().toString(), behandlingsId: String = UUID.randomUUID().toString(), tema: String = "AAP") =
	InnsendingTopicMsgBuilder()
		.withSkjemanr("NAV 11-12.10")
		.withTittel("Kjøreliste for godkjent bruk av egen bil")
		.withInnsendingsId(behandlingsId)
		.withArkivtema(tema)
		.withTestDokumenter(
			mutableListOf(
				TestDokument(
					skjemanummer = "NAV 11-12.10",
					erHovedskjema = true,
					tittel = "Kjøreliste for godkjent bruk av egen bil",
					uuids = listOf(fileId)
				)
			)
		)
		.build()
