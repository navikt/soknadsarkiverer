package no.nav.soknad.arkivering.soknadsarkiverer.converter

import no.nav.soknad.arkivering.soknadsarkiverer.MottattDokumentBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.MottattVariantBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.SoknadarkivschemaBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.soknadarkivering.avroschemas.Soknadstyper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*

class MessageConverterTests {
	private val messageConverter = MessageConverter()

	@Test
	fun `Happy case - Soknad - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = listOf(FilElementDto(uuid, "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder()
			.withSoknadstype(Soknadstyper.SOKNAD)

			.withMottatteDokumenter(MottattDokumentBuilder()
				.withTittel(tittel)
				.withSkjemanummer(skjemanummer)
				.withMottatteVarianter(MottattVariantBuilder()
					.withUuid(uuid)
					.build())
				.build())
			.build()

		val joarkData = messageConverter.createJoarkData(schema, files)

		assertEquals("Søknad til $tittel", joarkData.tittel)
		assertEquals(1, joarkData.dokumenter.size)
		assertEquals(tittel, joarkData.dokumenter[0].tittel)
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

			.withMottatteDokumenter(MottattDokumentBuilder()
				.withTittel(tittel)
				.withSkjemanummer(skjemanummer)
				.withMottatteVarianter(MottattVariantBuilder()
					.withUuid(uuid)
					.build())
				.build())
			.build()

		val joarkData = messageConverter.createJoarkData(schema, files)

		assertEquals("Ettersendelse til $tittel", joarkData.tittel)
		assertEquals(1, joarkData.dokumenter.size)
		assertEquals(tittel, joarkData.dokumenter[0].tittel)
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
		val files = listOf(FilElementDto(uuid0, "apa".toByteArray()), FilElementDto(uuid1, "bepa".toByteArray()),
			FilElementDto(uuid2, "cepa".toByteArray()), FilElementDto(uuid3, "depa".toByteArray()))


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
					.withMottatteVarianter(MottattVariantBuilder()
						.withUuid(uuid0)
						.withFilnavn("apa")
						.withfiltype("PDFA")
						.build()
					)
					.build(),

				MottattDokumentBuilder()
					.withErHovedskjema(false)
					.withTittel("Cepa depa")
					.withMottatteVarianter(MottattVariantBuilder()
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
					.withMottatteVarianter(MottattVariantBuilder()
						.withUuid(uuid3)
						.build()
					)
					.build()
			)
			.build()

		val joarkData = messageConverter.createJoarkData(schema, files)

		assertEquals("INNGAAENDE", joarkData.journalpostType)
		assertEquals("NAV_NO", joarkData.kanal)
		assertEquals("FNR", joarkData.bruker.idType)
		assertEquals(schema.getFodselsnummer(), joarkData.bruker.id)
		assertEquals(innsendtDate.format(DateTimeFormatter.ISO_DATE), joarkData.datoMottatt)
		assertEquals(schema.getBehandlingsid(), joarkData.eksternReferanseId)
		assertEquals(schema.getArkivtema(), joarkData.tema)
		assertEquals("Søknad til Apa bepa", joarkData.tittel)

		assertEquals(3, joarkData.dokumenter.size)
		assertEquals(schema.getMottatteDokumenter()[0].getTittel(), joarkData.dokumenter[0].tittel)
		assertEquals(schema.getMottatteDokumenter()[0].getSkjemanummer(), joarkData.dokumenter[0].brevkode)
		assertEquals("SOK", joarkData.dokumenter[0].dokumentKategori)

		assertEquals(1, joarkData.dokumenter[0].dokumentvarianter.size)
		assertEquals(schema.getMottatteDokumenter()[0].getMottatteVarianter()[0].getFilnavn(), joarkData.dokumenter[0].dokumentvarianter[0].filnavn)
		assertEquals(schema.getMottatteDokumenter()[0].getMottatteVarianter()[0].getFiltype(), joarkData.dokumenter[0].dokumentvarianter[0].filtype)
		assertEquals(schema.getMottatteDokumenter()[0].getMottatteVarianter()[0].getVariantformat(), joarkData.dokumenter[0].dokumentvarianter[0].variantformat)
		assertEquals(files[0].fil, joarkData.dokumenter[0].dokumentvarianter[0].fysiskDokument)


		assertEquals(schema.getMottatteDokumenter()[1].getTittel(), joarkData.dokumenter[1].tittel)
		assertEquals(schema.getMottatteDokumenter()[1].getSkjemanummer(), joarkData.dokumenter[1].brevkode)
		assertEquals("SOK", joarkData.dokumenter[1].dokumentKategori)

		assertEquals(2, joarkData.dokumenter[1].dokumentvarianter.size)
		assertEquals(schema.getMottatteDokumenter()[1].getMottatteVarianter()[0].getFilnavn(), joarkData.dokumenter[1].dokumentvarianter[0].filnavn)
		assertEquals(schema.getMottatteDokumenter()[1].getMottatteVarianter()[0].getFiltype(), joarkData.dokumenter[1].dokumentvarianter[0].filtype)
		assertEquals(schema.getMottatteDokumenter()[1].getMottatteVarianter()[0].getVariantformat(), joarkData.dokumenter[1].dokumentvarianter[0].variantformat)
		assertEquals(files[1].fil, joarkData.dokumenter[1].dokumentvarianter[0].fysiskDokument)

		assertEquals(schema.getMottatteDokumenter()[1].getMottatteVarianter()[1].getFilnavn(), joarkData.dokumenter[1].dokumentvarianter[1].filnavn)
		assertEquals(schema.getMottatteDokumenter()[1].getMottatteVarianter()[1].getFiltype(), joarkData.dokumenter[1].dokumentvarianter[1].filtype)
		assertEquals(schema.getMottatteDokumenter()[1].getMottatteVarianter()[1].getVariantformat(), joarkData.dokumenter[1].dokumentvarianter[1].variantformat)
		assertEquals(files[2].fil, joarkData.dokumenter[1].dokumentvarianter[1].fysiskDokument)


		assertEquals(schema.getMottatteDokumenter()[2].getTittel(), joarkData.dokumenter[2].tittel)
		assertEquals(schema.getMottatteDokumenter()[2].getSkjemanummer(), joarkData.dokumenter[2].brevkode)
		assertEquals("SOK", joarkData.dokumenter[2].dokumentKategori)

		assertEquals(1, joarkData.dokumenter[2].dokumentvarianter.size)
		assertEquals(schema.getMottatteDokumenter()[2].getMottatteVarianter()[0].getFilnavn(), joarkData.dokumenter[2].dokumentvarianter[0].filnavn)
		assertEquals(schema.getMottatteDokumenter()[2].getMottatteVarianter()[0].getFiltype(), joarkData.dokumenter[2].dokumentvarianter[0].filtype)
		assertEquals(schema.getMottatteDokumenter()[2].getMottatteVarianter()[0].getVariantformat(), joarkData.dokumenter[2].dokumentvarianter[0].variantformat)
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
			messageConverter.createJoarkData(schema, files)
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
			messageConverter.createJoarkData(schema, files)
		}
	}

	@Test
	fun `No MottatteDokumenter -- should throw exception`() {
		val files = listOf(FilElementDto(UUID.randomUUID().toString(), "apa".toByteArray()))

		val schema = SoknadarkivschemaBuilder().build()


		assertThrows<Exception> {
			messageConverter.createJoarkData(schema, files)
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
			messageConverter.createJoarkData(schema, files)
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
			messageConverter.createJoarkData(schema, emptyList())
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
			messageConverter.createJoarkData(schema, files)
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
			messageConverter.createJoarkData(schema, files)
		}
	}
}
