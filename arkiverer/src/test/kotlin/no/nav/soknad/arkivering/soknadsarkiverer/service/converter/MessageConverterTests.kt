package no.nav.soknad.arkivering.soknadsarkiverer.service.converter

import com.google.gson.Gson
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattDokumentBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattVariantBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.SoknadarkivschemaBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*

class MessageConverterTests {

	@Test
	fun `Happy case - Soknad - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid, "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withMottatteDokumenter(
				MottattDokumentBuilder()
					.withTittel(tittel)
					.withSkjemanummer(skjemanummer)
					.withMottatteVarianter(
						MottattVariantBuilder()
							.withUuid(uuid)
							.build()
					)
					.build()
			)
			.build()

		val joarkData = createOpprettJournalpostRequest(schema, files)

		assertEquals(tittel, joarkData.tittel)
		assertEquals(1, joarkData.dokumenter.size)
		assertEquals(joarkData.tittel, joarkData.dokumenter[0].tittel)
		assertEquals(skjemanummer, joarkData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Ettersending - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid, "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withSoknadstype(Soknadstyper.ETTERSENDING)
			.withMottatteDokumenter(
				MottattDokumentBuilder()
					.withTittel(tittel)
					.withSkjemanummer(skjemanummer)
					.withMottatteVarianter(
						MottattVariantBuilder()
							.withUuid(uuid)
							.build()
					)
					.build()
			)
			.build()

		val joarkData = createOpprettJournalpostRequest(schema, files)

		assertEquals("Ettersendelse til apa bepa", joarkData.tittel)
		assertEquals(1, joarkData.dokumenter.size)
		assertEquals(joarkData.tittel, joarkData.dokumenter[0].tittel)
		assertEquals("NAVe 11-13.06", joarkData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Large example - should convert correctly`() {
		val innsendtDate = LocalDate.of(2020, 3, 17)
		val innsendtDateTime = LocalDateTime.of(innsendtDate, LocalTime.now())

		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val uuid2 = UUID.randomUUID().toString()
		val uuid3 = UUID.randomUUID().toString()
		val files = listOf(
			FilElementDto(uuid0, "apa".toByteArray()), FilElementDto(uuid1, "bepa".toByteArray()),
			FilElementDto(uuid2, "cepa".toByteArray()), FilElementDto(uuid3, "depa".toByteArray())
		)


		val schema = SoknadarkivschemaBuilder()
			.withArkivtema("AAP")
			.withFodselsnummer("09876543210")
			.withInnsendtDato(innsendtDateTime)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withBehandlingsid("apabepacepa")
			.withMottatteDokumenter(

				MottattDokumentBuilder()
					.withErHovedskjema(true)
					.withTittel("Apa bepa")
					.withSkjemanummer("NAV 11-13.06")
					.withMottatteVarianter(
						MottattVariantBuilder()
							.withUuid(uuid0)
							.withFilnavn("apa")
							.withfiltype("PDFA")
							.build()
					)
					.build(),

				MottattDokumentBuilder()
					.withErHovedskjema(false)
					.withTittel("Cepa depa")
					.withMottatteVarianter(
						MottattVariantBuilder()
							.withUuid(uuid1)
							.withFilnavn("bepa")
							.withfiltype("jpg")
							.withVariantformat("ORIGINAL")
							.build(),

						MottattVariantBuilder()
							.withUuid(uuid2)
							.withFilnavn("cepa")
							.build()
					)
					.build(),

				MottattDokumentBuilder()
					.withErHovedskjema(false)
					.withTittel("Epa Fepa")
					.withMottatteVarianter(
						MottattVariantBuilder()
							.withUuid(uuid3)
							.build()
					)
					.build()
			)
			.build()

		val joarkData = createOpprettJournalpostRequest(schema, files)

		assertEquals("INNGAAENDE", joarkData.journalpostType)
		assertEquals("NAV_NO", joarkData.kanal)
		assertEquals("FNR", joarkData.bruker.idType)
		assertEquals(schema.fodselsnummer, joarkData.bruker.id)
		assertEquals(innsendtDate.format(DateTimeFormatter.ISO_DATE), joarkData.datoMottatt)
		assertEquals(schema.behandlingsid, joarkData.eksternReferanseId)
		assertEquals(schema.arkivtema, joarkData.tema)
		assertEquals(joarkData.tittel, joarkData.dokumenter[0].tittel)

		assertEquals(3, joarkData.dokumenter.size)
		assertEquals(schema.mottatteDokumenter[0].tittel, joarkData.dokumenter[0].tittel)
		assertEquals(schema.mottatteDokumenter[0].skjemanummer, joarkData.dokumenter[0].brevkode)
		assertEquals("SOK", joarkData.dokumenter[0].dokumentKategori)

		assertEquals(1, joarkData.dokumenter[0].dokumentvarianter.size)
		assertEquals(schema.mottatteDokumenter[0].mottatteVarianter[0].filnavn, joarkData.dokumenter[0].dokumentvarianter[0].filnavn)
		assertEquals(schema.mottatteDokumenter[0].mottatteVarianter[0].filtype, joarkData.dokumenter[0].dokumentvarianter[0].filtype)
		assertEquals(schema.mottatteDokumenter[0].mottatteVarianter[0].variantformat, joarkData.dokumenter[0].dokumentvarianter[0].variantformat)
		assertEquals(files[0].fil, joarkData.dokumenter[0].dokumentvarianter[0].fysiskDokument)


		assertEquals(schema.mottatteDokumenter[1].tittel, joarkData.dokumenter[1].tittel)
		assertEquals(schema.mottatteDokumenter[1].skjemanummer, joarkData.dokumenter[1].brevkode)
		assertEquals("SOK", joarkData.dokumenter[1].dokumentKategori)

		assertEquals(2, joarkData.dokumenter[1].dokumentvarianter.size)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[0].filnavn, joarkData.dokumenter[1].dokumentvarianter[0].filnavn)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[0].filtype, joarkData.dokumenter[1].dokumentvarianter[0].filtype)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[0].variantformat, joarkData.dokumenter[1].dokumentvarianter[0].variantformat)
		assertEquals(files[1].fil, joarkData.dokumenter[1].dokumentvarianter[0].fysiskDokument)

		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[1].filnavn, joarkData.dokumenter[1].dokumentvarianter[1].filnavn)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[1].filtype, joarkData.dokumenter[1].dokumentvarianter[1].filtype)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[1].variantformat, joarkData.dokumenter[1].dokumentvarianter[1].variantformat)
		assertEquals(files[2].fil, joarkData.dokumenter[1].dokumentvarianter[1].fysiskDokument)


		assertEquals(schema.mottatteDokumenter[2].tittel, joarkData.dokumenter[2].tittel)
		assertEquals(schema.mottatteDokumenter[2].skjemanummer, joarkData.dokumenter[2].brevkode)
		assertEquals("SOK", joarkData.dokumenter[2].dokumentKategori)

		assertEquals(1, joarkData.dokumenter[2].dokumentvarianter.size)
		assertEquals(schema.mottatteDokumenter[2].mottatteVarianter[0].filnavn, joarkData.dokumenter[2].dokumentvarianter[0].filnavn)
		assertEquals(schema.mottatteDokumenter[2].mottatteVarianter[0].filtype, joarkData.dokumenter[2].dokumentvarianter[0].filtype)
		assertEquals(schema.mottatteDokumenter[2].mottatteVarianter[0].variantformat, joarkData.dokumenter[2].dokumentvarianter[0].variantformat)
		assertEquals(files[3].fil, joarkData.dokumenter[2].dokumentvarianter[0].fysiskDokument)
	}


	@Test
	fun `Several Hovedskjemas -- should throw exception`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid0, "apa".toByteArray()), FilElementDto(uuid1, "bepa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(

				MottattDokumentBuilder()
					.withErHovedskjema(true)
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid0).build())
					.build(),

				MottattDokumentBuilder()
					.withErHovedskjema(true)
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid1).build())
					.build()
			)
			.build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}

	@Test
	fun `No Hovedskjema -- should throw exception`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid0, "apa".toByteArray()), FilElementDto(uuid1, "bepa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(

				MottattDokumentBuilder()
					.withErHovedskjema(false)
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid0).build())
					.build(),

				MottattDokumentBuilder()
					.withErHovedskjema(false)
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid1).build())
					.build()
			)
			.build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}

	@Test
	fun `No MottatteDokumenter -- should throw exception`() {
		val files = listOf(FilElementDto(UUID.randomUUID().toString(), "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder().build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}

	@Test
	fun `No MottatteVarianter -- should throw exception`() {
		val files = listOf(FilElementDto(UUID.randomUUID().toString(), "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(
				MottattDokumentBuilder().withErHovedskjema(true).build()
			)
			.build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}

	@Test
	fun `No files -- should throw exception`() {

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(
				MottattDokumentBuilder()
					.withMottatteVarianter(MottattVariantBuilder().withUuid(UUID.randomUUID().toString()).build())
					.build()
			)
			.build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, emptyList())
		}
	}

	@Test
	fun `No matching files -- should throw exception`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val uuidNotInFileList = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid0, "apa".toByteArray()), FilElementDto(uuid1, "bepa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(
				MottattDokumentBuilder()
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuidNotInFileList).build())
					.build()
			)
			.build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}

	@Test
	fun `Matching file is null -- should throw exception`() {
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid, null))

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(
				MottattDokumentBuilder()
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid).build())
					.build()
			)
			.build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}


	private fun convertJsonTilInnsendtSoknad(): Soknadarkivschema {
		val innsendtSoknadJson = "{" +
			"\"behandlingsid\": \"10010G1MJ\", " +
			"\"fodselsnummer\": \"\", " +
			"\"arkivtema\": \"BIL\", " +
			"\"innsendtDato\": 1607419847, " +
			"\"soknadstype\": \"ETTERSENDING\", " +
			"\"mottatteDokumenter\": [" +
			"{\"skjemanummer\": \"NAV 10-07.40\", " +
			"\"erHovedskjema\": true, " +
			"\"tittel\": \"Søknad om stønad til anskaffelse av motorkjøretøy\", " +
			"\"mottatteVarianter\": [" +
			"{\"uuid\": \"43121902-305c-4b31-b9ab-581f89f8da2c\", " +
			"\"filnavn\": \"NAV 10-07.40.pdfa\", " +
			"\"filtype\": \"PDF/A\", " +
			"\"variantformat\": \"ARKIV\"}" +
			"]}, " +
			"{\"skjemanummer\": \"L9\", " +
			"\"erHovedskjema\": false, " +
			"\"tittel\": \"Legeerklæring\", " +
			"\"mottatteVarianter\": [" +
			"{\"uuid\": \"6e8db379-91c0-4395-ad95-c72dabea421c\", " +
			"\"filnavn\": \"L9\", " +
			"\"filtype\": \"PDF\", " +
			"\"variantformat\": \"ARKIV\"}" +
			"]}, " +
			"{\"skjemanummer\": \"Z3\", " +
			"\"erHovedskjema\": false, " +
			"\"tittel\": \"Beskrivelse av funksjonsnedsettelse\", " +
			"\"mottatteVarianter\": [" +
			"{\"uuid\": \"31115802-706f-4cde-8392-cd19b0edc777\", " +
			"\"filnavn\": \"Z3\", " +
			"\"filtype\": \"PDF\", " +
			"\"variantformat\": \"ARKIV\"}" +
			"]}, " +
			"{\"skjemanummer\": \"L7\", " +
			"\"erHovedskjema\": false, " +
			"\"tittel\": \"Kvitteringsside for dokumentinnsending\", " +
			"\"mottatteVarianter\": [" +
			"{\"uuid\": \"7311e586-c424-4898-a6b1-a2085ecf461d\", " +
			"\"filnavn\": \"L7\", " +
			"\"filtype\": \"PDF\", " +
			"\"variantformat\": \"ARKIV\"}" +
			"]}]}"
		val gson = Gson()

		return gson.fromJson(innsendtSoknadJson, Soknadarkivschema::class.java)
	}

	@Test
	fun `Real case - Ettersending - should convert correctly`() {
		val excpeted = "NAVe 10-07.40"
		val files = listOf(
			FilElementDto("43121902-305c-4b31-b9ab-581f89f8da2c", "apa".toByteArray()),
			FilElementDto("6e8db379-91c0-4395-ad95-c72dabea421c", "apa".toByteArray()),
			FilElementDto("31115802-706f-4cde-8392-cd19b0edc777", "apa".toByteArray()),
			FilElementDto("7311e586-c424-4898-a6b1-a2085ecf461d", "apa".toByteArray())
		)

		val schema = convertJsonTilInnsendtSoknad()

		val joarkData = createOpprettJournalpostRequest(schema, files)

		assertEquals(4, joarkData.dokumenter.size)
		assertEquals(excpeted, joarkData.dokumenter[0].brevkode)
		assertEquals(joarkData.dokumenter[0].tittel, joarkData.tittel)
		assertEquals("Ettersendelse til søknad om stønad til anskaffelse av motorkjøretøy", joarkData.tittel)
		assertEquals(joarkData.dokumenter[1].tittel, "Legeerklæring")
		assertEquals(joarkData.dokumenter[2].tittel, "Beskrivelse av funksjonsnedsettelse")
	}
}
