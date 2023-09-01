package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.avroschemas.MottattDokument
import no.nav.soknad.arkivering.avroschemas.MottattVariant
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import java.time.LocalDateTime
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

fun createSoknadarkivschema(fileIds: List<String>, behandlingsId: String = UUID.randomUUID().toString()) =
	SoknadarkivschemaBuilder()
		.withBehandlingsid(behandlingsId)
		.withMottatteDokumenter(MottattDokumentBuilder()
			.withMottatteVarianter(
				fileIds.map { MottattVariantBuilder().withUuid(it).build() }
			)
			.build())
		.build()
