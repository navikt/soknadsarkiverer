package no.nav.soknad.arkivering.soknadsarkiverer.service.converter

import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.utils.InnsendingTopicMsgBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.TestDokument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.util.AssertionErrors.assertTrue
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class MessageConverterTests {

	@Test
	fun `Happy case - Soknad - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),	UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withInnsendingsId(uuid)
			.withTestDokumenter(files)
			.build()

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val arkivData = createOpprettJournalpostRequest(schema, uploadedfiles)

		assertEquals(tittel, arkivData.tittel)
		assertEquals(2, arkivData.dokumenter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals(skjemanummer, arkivData.dokumenter[0].brevkode)
		assertEquals(schema.innsendingsId, arkivData.eksternReferanseId)
		assertEquals(schema.kanal, arkivData.kanal)
	}

	@Test
	fun `Happy case - NoLoginSoknad - should convert correctly`() {
		val innsendingsId = UUID.randomUUID().toString()
		val innsendtDato = OffsetDateTime.now()
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val tema = "AAP"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),	UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withInnsendingsId(innsendingsId)
			.withInnsendtDato(innsendtDato)
			.withInnlogget(false)
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withArkivtema(tema)
			.withTestDokumenter(files)
			.build()

		val arkivData = createOpprettJournalpostRequest(schema, uploadedfiles)

		assertEquals(tittel, arkivData.tittel)
		assertEquals(2, arkivData.dokumenter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals(skjemanummer, arkivData.dokumenter[0].brevkode)
		assertEquals(schema.innsendingsId, arkivData.eksternReferanseId)
		assertEquals(schema.kanal, arkivData.kanal)
		assertTrue("Expected kanal to be NAV_NO_UINNLOGGET when innlogget is false, but was ${arkivData.kanal}", !schema.innlogget && arkivData.kanal == "NAV_NO_UINNLOGGET",)
	}


	@Test
	fun `Happy case - NoLoginSoknad - with no brukerDto should convert correctly`() {
		val innsendingsId = UUID.randomUUID().toString()
		val innsendtDato = OffsetDateTime.now()
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val tema = "AAP"
		val avsenderNavn = "Avsender Navn"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),	UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withInnsendingsId(innsendingsId)
			.withInnsendtDato(innsendtDato)
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withArkivtema(tema)
			.withBrukerId(null)
			.withAvsenderId(null)
			.withAvsenderIdType(null)
			.withAvsenderNavn(avsenderNavn)
			.withTestDokumenter(files)
			.build()

		val arkivData = createOpprettJournalpostRequest(schema, uploadedfiles)

		assertEquals(tittel, arkivData.tittel)
		assertEquals(2, arkivData.dokumenter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals(skjemanummer, arkivData.dokumenter[0].brevkode)
		assertEquals(schema.innsendingsId, arkivData.eksternReferanseId)
		assertEquals(schema.kanal, arkivData.kanal)
		assertEquals(null, arkivData.bruker)
		assertEquals(schema.avsenderDto.id, arkivData.avsenderMottaker.id)
		assertEquals(null, arkivData.avsenderMottaker.idType)
		assertEquals(schema.avsenderDto.navn, arkivData.avsenderMottaker.navn)

	}

	@Test
	fun `Happy case - Ettersending - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),	UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withInnsendingsId(uuid)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(files)
			.build()

		val arkivData = createOpprettJournalpostRequest(schema, uploadedfiles)

		assertEquals("Ettersendelse til apa bepa", arkivData.tittel)
		assertEquals(2, arkivData.dokumenter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals("NAVe 11-13.06", arkivData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Ettersending - should filter variantFormat duplicates`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withInnsendingsId(uuid)
			.withEttersendelseTilId(UUID.randomUUID().toString())
			.withTestDokumenter(files)
			.build()

		val arkivData = createOpprettJournalpostRequest(schema, uploadedfiles)

		assertEquals("Ettersendelse til apa bepa", arkivData.tittel)
		assertEquals(2, arkivData.dokumenter.size)
		assertEquals(2, arkivData.dokumenter.first().dokumentvarianter.size)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals("NAVe 11-13.06", arkivData.dokumenter[0].brevkode)
	}

	@Test
	fun `Happy case - Large example - should convert correctly`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"
		val uuid = UUID.randomUUID().toString()
		val innsendtDateTime = OffsetDateTime.of(2020, 3, 17, 13, 37, 17, 0, ZoneOffset.ofTotalSeconds(60*60))

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "W2", erHovedskjema = false, tittel = "Attachment", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withArkivtema("AAP")
			.withInnsendingsId(uuid)
			.withInnsendtDato(innsendtDateTime)
			.withEttersendelseTilId(null)
			.withBrukerId("09876543210")
			.withBrukerIdType("FNR")
			.withAvsenderId("01234567890")
			.withAvsenderIdType("FNR")
			.withTestDokumenter(files)
			.withKanal("NAV_NO")
			.build()

		val arkivData = createOpprettJournalpostRequest(schema, uploadedfiles)

		assertEquals("INNGAAENDE", arkivData.journalpostType)
		assertEquals("NAV_NO", arkivData.kanal)
		assertEquals("FNR", arkivData.bruker?.idType)
		assertEquals(schema.brukerDto?.id, arkivData.bruker?.id)
		assertEquals("2020-03-17T13:37:17+01:00", arkivData.datoMottatt)
		assertEquals(schema.innsendingsId, arkivData.eksternReferanseId)
		assertEquals(schema.arkivtema, arkivData.tema)
		assertEquals(arkivData.tittel, arkivData.dokumenter[0].tittel)
		assertEquals(3, arkivData.dokumenter.size)

		assertEquals(schema.dokumenter[0].tittel, arkivData.dokumenter[0].tittel)
		assertEquals(schema.dokumenter[0].skjemanummer, arkivData.dokumenter[0].brevkode)
		assertEquals("SOK", arkivData.dokumenter[0].dokumentKategori)
		assertEquals(2, arkivData.dokumenter[0].dokumentvarianter.size)
		assertEquals(schema.dokumenter[0].varianter[0].filnavn, arkivData.dokumenter[0].dokumentvarianter[0].filnavn)
		assertEquals(schema.dokumenter[0].varianter[1].filtype, arkivData.dokumenter[0].dokumentvarianter[1].filtype)
		assertEquals(schema.dokumenter[0].varianter[0].variantFormat, arkivData.dokumenter[0].dokumentvarianter[0].variantformat)
		assertEquals(uploadedfiles[0].content, arkivData.dokumenter[0].dokumentvarianter[0].fysiskDokument)


		assertEquals(schema.dokumenter[1].tittel, arkivData.dokumenter[1].tittel)
		assertEquals(schema.dokumenter[1].skjemanummer, arkivData.dokumenter[1].brevkode)
		assertEquals("SOK", arkivData.dokumenter[1].dokumentKategori)
		assertEquals(1, arkivData.dokumenter[1].dokumentvarianter.size)
		assertEquals(schema.dokumenter[1].varianter[0].filnavn, arkivData.dokumenter[1].dokumentvarianter[0].filnavn)
		assertEquals(schema.dokumenter[1].varianter[0].filtype.uppercase(), arkivData.dokumenter[1].dokumentvarianter[0].filtype)
		assertEquals(schema.dokumenter[1].varianter[0].variantFormat, arkivData.dokumenter[1].dokumentvarianter[0].variantformat)
		assertEquals(uploadedfiles[2].content, arkivData.dokumenter[1].dokumentvarianter[0].fysiskDokument)

		assertEquals(schema.dokumenter[2].tittel, arkivData.dokumenter[2].tittel)
		assertEquals(schema.dokumenter[2].skjemanummer, arkivData.dokumenter[2].brevkode)
		assertEquals("SOK", arkivData.dokumenter[2].dokumentKategori)
		assertEquals(1, arkivData.dokumenter[2].dokumentvarianter.size)
		assertEquals(schema.dokumenter[2].varianter[0].filnavn, arkivData.dokumenter[2].dokumentvarianter[0].filnavn)
		assertEquals(schema.dokumenter[2].varianter[0].filtype, arkivData.dokumenter[2].dokumentvarianter[0].filtype)
		assertEquals(schema.dokumenter[2].varianter[0].variantFormat, arkivData.dokumenter[2].dokumentvarianter[0].variantformat)
		assertEquals(uploadedfiles[3].content, arkivData.dokumenter[2].dokumentvarianter[0].fysiskDokument)
	}


	@Test
	fun `Several Hovedskjemas -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "W2", erHovedskjema = false, tittel = "Attachment", uuids = listOf(UUID.randomUUID().toString()) )
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, uploadedfiles)
		}
	}

	@Test
	fun `Several documentvariants -- should check main document variantformats and filter duplicates`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		assertEquals(2, createOpprettJournalpostRequest(schema, uploadedfiles).dokumenter.first().dokumentvarianter.size)
	}

	@Test
	fun `No Hovedskjema -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)

		val uploadedfiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, uploadedfiles)
		}
	}

	@Test
	fun `No MottatteDokumenter -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(mutableListOf())
			.build()

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, emptyList())
		}
	}

	@Test
	fun `No MottatteVarianter -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)

		val uploadedfiles = files.filter{it.skjemanummer!="L7"}.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, uploadedfiles)
		}
	}

	@Test
	fun `No files -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)

		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, emptyList())
		}
	}

	@Test
	fun `No matching files -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)
		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		val otherFiles = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)
		val otherUploadedFiles = otherFiles.filter{it.skjemanummer!="L7"}.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, "apa".toByteArray(), ResponseStatus.Ok) }

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, otherUploadedFiles)
		}
	}

	@Test
	fun `Matching file is null -- should throw exception`() {
		val tittel = "Apa bepa"
		val skjemanummer = "NAV 11-13.06"

		val files = mutableListOf (
			TestDokument( skjemanummer = skjemanummer, erHovedskjema = true, tittel = tittel, uuids = listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()) ),
			TestDokument( skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering", uuids = listOf(UUID.randomUUID().toString()) ),
		)
		val schema = InnsendingTopicMsgBuilder()
			.withTittel(tittel)
			.withSkjemanr(skjemanummer)
			.withTestDokumenter(files)
			.build()

		val uploadedFiles = files.map{vedlegg -> vedlegg.uuids}.flatten().map { uuid -> FileInfo(uuid, null, ResponseStatus.Ok) }

		assertThrows<Exception> {
			createOpprettJournalpostRequest(schema, uploadedFiles)
		}
	}
}
