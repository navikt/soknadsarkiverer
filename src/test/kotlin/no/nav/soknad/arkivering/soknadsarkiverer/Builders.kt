package no.nav.soknad.arkivering.soknadsarkiverer

import no.nav.soknad.arkivering.avroschemas.MottattDokument
import no.nav.soknad.arkivering.avroschemas.MottattVariant
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import java.time.LocalDateTime
import java.time.ZoneOffset


class SoknadarkivschemaBuilder {
	private var behandlingsid: String = "behandlingsid"
	private var fodselsnummer: String = "12345687901"
	private var arkivtema: String = "BIL"
	private var innsendtDato: Long = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
	private var soknadstype: Soknadstyper = Soknadstyper.SOKNAD
	private var mottatteDokumenter: MutableList<MottattDokument> = mutableListOf()

	fun withBehandlingsid(behandlingsid: String): SoknadarkivschemaBuilder {
		this.behandlingsid = behandlingsid
		return this
	}

	fun withFodselsnummer(fodselsnummer: String): SoknadarkivschemaBuilder {
		this.fodselsnummer = fodselsnummer
		return this
	}

	fun withArkivtema(arkivtema: String): SoknadarkivschemaBuilder {
		this.arkivtema = arkivtema
		return this
	}

	fun withInnsendtDato(innsendtDato: LocalDateTime): SoknadarkivschemaBuilder {
		this.innsendtDato = innsendtDato.toEpochSecond(ZoneOffset.UTC)
		return this
	}

	fun withSoknadstype(soknadstype: Soknadstyper): SoknadarkivschemaBuilder {
		this.soknadstype = soknadstype
		return this
	}

	fun withMottatteDokumenter(vararg mottatteDokumenter: MottattDokument): SoknadarkivschemaBuilder {
		this.mottatteDokumenter.addAll(mottatteDokumenter)
		return this
	}

	fun build() = Soknadarkivschema(behandlingsid, fodselsnummer, arkivtema, innsendtDato, soknadstype, mottatteDokumenter)
}

class MottattDokumentBuilder {
	private var skjemanummer: String = "NAV 11-13.05"
	private var erHovedskjema: Boolean = true
	private var tittel: String = "Søknad om arbeidsavklaringspenger"
	private var mottatteVarianter: MutableList<MottattVariant> = mutableListOf()

	fun withSkjemanummer(skjemanummer: String): MottattDokumentBuilder {
		this.skjemanummer = skjemanummer
		return this
	}

	fun withErHovedskjema(erHovedskjema: Boolean): MottattDokumentBuilder {
		this.erHovedskjema = erHovedskjema
		return this
	}

	fun withTittel(tittel: String): MottattDokumentBuilder {
		this.tittel = tittel
		return this
	}

	fun withMottatteVarianter(vararg mottatteVarianter: MottattVariant): MottattDokumentBuilder {
		this.mottatteVarianter.addAll(mottatteVarianter)
		return this
	}

	fun build() = MottattDokument(skjemanummer, erHovedskjema, tittel, mottatteVarianter)
}

class MottattVariantBuilder {
	private var uuid: String = "uuid"
	private var filnavn: String = "filnavn"
	private var filtype: String = "PDFA"
	private var variantformat: String = "ARKIV"

	fun withUuid(uuid: String): MottattVariantBuilder {
		this.uuid = uuid
		return this
	}

	fun withFilnavn(filnavn: String): MottattVariantBuilder {
		this.filnavn = filnavn
		return this
	}

	fun withfiltype(filtype: String): MottattVariantBuilder {
		this.filtype = filtype
		return this
	}

	fun withVariantformat(variantformat: String): MottattVariantBuilder {
		this.variantformat = variantformat
		return this
	}

	fun build() = MottattVariant(uuid, filnavn, filtype, variantformat)
}
