package no.nav.soknad.arkivering.soknadsarkiverer.service.converter

import com.google.gson.Gson
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattDokumentBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.MottattVariantBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.SoknadarkivschemaBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.*

class MessageConverterTests {

	@Test
	fun `Happy case - Soknad - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok))

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

		val arkivData = createOpprettJournalpostRequest(schema, files)

		assertEquals(tittel, arkivData.tittel)
		assertEquals(1, arkivData.dokumenter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals(skjemanummer, arkivData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Ettersending - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok))

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

		val arkivData = createOpprettJournalpostRequest(schema, files)

		assertEquals("Ettersendelse til apa bepa", arkivData.tittel)
		assertEquals(1, arkivData.dokumenter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals("NAVe 11-13.06", arkivData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Ettersending - should filter variantFormat duplicates`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok))

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
					.withMottatteVarianter(
						MottattVariantBuilder()
							.withUuid(uuid)
							.build()
					)
					.build()
			)
			.build()

		val arkivData = createOpprettJournalpostRequest(schema, files)

		assertEquals("Ettersendelse til apa bepa", arkivData.tittel)
		assertEquals(1, arkivData.dokumenter.size)
		assertEquals(1, arkivData.dokumenter.first().dokumentvarianter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals("NAVe 11-13.06", arkivData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Large example - should convert correctly`() {
		val innsendtDateTime = LocalDateTime.of(2020, 3, 17, 12, 37, 17)

		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val uuid2 = UUID.randomUUID().toString()
		val uuid3 = UUID.randomUUID().toString()
		val files = listOf(
			FileInfo(uuid0, "apa".toByteArray(), ResponseStatus.Ok), FileInfo(uuid1, "bepa".toByteArray(), ResponseStatus.Ok),
			FileInfo(uuid2, "cepa".toByteArray(), ResponseStatus.Ok), FileInfo(uuid3, "depa".toByteArray(), ResponseStatus.Ok)
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

		val arkivData = createOpprettJournalpostRequest(schema, files)

		assertEquals("INNGAAENDE", arkivData.journalpostType)
		assertEquals("NAV_NO", arkivData.kanal)
		assertEquals("FNR", arkivData.bruker.idType)
		assertEquals(schema.fodselsnummer, arkivData.bruker.id)
		assertEquals("2020-03-17T13:37:17+01:00", arkivData.datoMottatt)
		assertEquals(schema.behandlingsid, arkivData.eksternReferanseId)
		assertEquals(schema.arkivtema, arkivData.tema)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)

		assertEquals(3, arkivData.dokumenter.size)
		assertEquals(schema.mottatteDokumenter[0].tittel, arkivData.dokumenter[0].tittel)
		assertEquals(schema.mottatteDokumenter[0].skjemanummer, arkivData.dokumenter[0].brevkode)
		assertEquals("SOK", arkivData.dokumenter[0].dokumentKategori)

		assertEquals(1, arkivData.dokumenter[0].dokumentvarianter.size)
		assertEquals(schema.mottatteDokumenter[0].mottatteVarianter[0].filnavn, arkivData.dokumenter[0].dokumentvarianter[0].filnavn)
		assertEquals(schema.mottatteDokumenter[0].mottatteVarianter[0].filtype, arkivData.dokumenter[0].dokumentvarianter[0].filtype)
		assertEquals(schema.mottatteDokumenter[0].mottatteVarianter[0].variantformat, arkivData.dokumenter[0].dokumentvarianter[0].variantformat)
		assertEquals(files[0].content, arkivData.dokumenter[0].dokumentvarianter[0].fysiskDokument)


		assertEquals(schema.mottatteDokumenter[1].tittel, arkivData.dokumenter[1].tittel)
		assertEquals(schema.mottatteDokumenter[1].skjemanummer, arkivData.dokumenter[1].brevkode)
		assertEquals("SOK", arkivData.dokumenter[1].dokumentKategori)

		assertEquals(2, arkivData.dokumenter[1].dokumentvarianter.size)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[0].filnavn, arkivData.dokumenter[1].dokumentvarianter[0].filnavn)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[0].filtype.uppercase(), arkivData.dokumenter[1].dokumentvarianter[0].filtype)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[0].variantformat, arkivData.dokumenter[1].dokumentvarianter[0].variantformat)
		assertEquals(files[1].content, arkivData.dokumenter[1].dokumentvarianter[0].fysiskDokument)

		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[1].filnavn, arkivData.dokumenter[1].dokumentvarianter[1].filnavn)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[1].filtype, arkivData.dokumenter[1].dokumentvarianter[1].filtype)
		assertEquals(schema.mottatteDokumenter[1].mottatteVarianter[1].variantformat, arkivData.dokumenter[1].dokumentvarianter[1].variantformat)
		assertEquals(files[2].content, arkivData.dokumenter[1].dokumentvarianter[1].fysiskDokument)


		assertEquals(schema.mottatteDokumenter[2].tittel, arkivData.dokumenter[2].tittel)
		assertEquals(schema.mottatteDokumenter[2].skjemanummer, arkivData.dokumenter[2].brevkode)
		assertEquals("SOK", arkivData.dokumenter[2].dokumentKategori)

		assertEquals(1, arkivData.dokumenter[2].dokumentvarianter.size)
		assertEquals(schema.mottatteDokumenter[2].mottatteVarianter[0].filnavn, arkivData.dokumenter[2].dokumentvarianter[0].filnavn)
		assertEquals(schema.mottatteDokumenter[2].mottatteVarianter[0].filtype, arkivData.dokumenter[2].dokumentvarianter[0].filtype)
		assertEquals(schema.mottatteDokumenter[2].mottatteVarianter[0].variantformat, arkivData.dokumenter[2].dokumentvarianter[0].variantformat)
		assertEquals(files[3].content, arkivData.dokumenter[2].dokumentvarianter[0].fysiskDokument)
	}


	@Test
	fun `Several Hovedskjemas -- should throw exception`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val files = listOf(FileInfo(uuid0, "apa".toByteArray(), ResponseStatus.Ok), FileInfo(uuid1, "bepa".toByteArray(), ResponseStatus.Ok))

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
	fun `Several documentvariants -- should check main document variantformats and filter duplicates`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val files = listOf(FileInfo(uuid0, "apa".toByteArray(), ResponseStatus.Ok), FileInfo(uuid1, "bepa".toByteArray(), ResponseStatus.Ok))

		val schema = SoknadarkivschemaBuilder()
			.withMottatteDokumenter(

				MottattDokumentBuilder()
					.withErHovedskjema(true)
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid0).build())
					.withMottatteVarianter(MottattVariantBuilder().withUuid(uuid1).build())
					.build(),
				)
			.build()


		assertEquals(1, createOpprettJournalpostRequest(schema, files).dokumenter.first().dokumentvarianter.size)
	}

	@Test
	fun `No Hovedskjema -- should throw exception`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val files = listOf(FileInfo(uuid0, "apa".toByteArray(), ResponseStatus.Ok), FileInfo(uuid1, "bepa".toByteArray(), ResponseStatus.Ok))

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
		val files = listOf(FileInfo(UUID.randomUUID().toString(), "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder().build()


		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, files)
		}
	}

	@Test
	fun `No MottatteVarianter -- should throw exception`() {
		val files = listOf(FileInfo(UUID.randomUUID().toString(), "apa".toByteArray()))

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
		val files = listOf(FileInfo(uuid0, "apa".toByteArray()), FileInfo(uuid1, "bepa".toByteArray()))

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
		val files = listOf(FileInfo(uuid, null))

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

	@Test
	fun `Real case - Ettersending - should convert correctly`() {
		val expected = "NAVe 10-07.40"
		val files = listOf(
			FileInfo("43121902-305c-4b31-b9ab-581f89f8da2c", "apa".toByteArray(), ResponseStatus.Ok),
			FileInfo("6e8db379-91c0-4395-ad95-c72dabea421c", "apa".toByteArray(), ResponseStatus.Ok),
			FileInfo("31115802-706f-4cde-8392-cd19b0edc777", "apa".toByteArray(), ResponseStatus.Ok),
			FileInfo("7311e586-c424-4898-a6b1-a2085ecf461d", "apa".toByteArray(), ResponseStatus.Ok)
		)

		val schema = convertJsonTilInnsendtSoknad()

		val arkivData = createOpprettJournalpostRequest(schema, files)

		assertEquals(4, arkivData.dokumenter.size)
		assertEquals(expected, arkivData.dokumenter[0].brevkode)
		assertEquals(arkivData.dokumenter[0].tittel, arkivData.tittel)
		assertEquals("Ettersendelse til søknad om stønad til anskaffelse av motorkjøretøy", arkivData.tittel)
		assertEquals(arkivData.dokumenter[1].tittel, "Legeerklæring")
		assertEquals(arkivData.dokumenter[2].tittel, "Beskrivelse av funksjonsnedsettelse")
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
}
