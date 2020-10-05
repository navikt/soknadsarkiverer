package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class JournalpostClient(private val appConfiguration: AppConfiguration,
												@Qualifier("archiveRestTemplate") private val restTemplate: RestTemplate): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun ping(): String {
		return restTemplate.getForObject("${appConfiguration.config.joarkUrl}/ping", String::class.java)!!
	}

	override fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FilElementDto>): String {
		try {
			logger.info("$key: Creating journalpost with behandlingsId=${soknadarkivschema.getBehandlingsid()}")
			val request: OpprettJournalpostRequest = createOpprettJournalpostRequest(soknadarkivschema, attachedFiles)

			val url = appConfiguration.config.joarkHost + appConfiguration.config.joarkUrl

			val filListe = request.dokumenter.map{it.dokumentvarianter.map{ it.filnavn +  ":"  + it.filtype +":" +it.fysiskDokument?.size + ":" + it.variantformat} }.toList()
			logger.info("$key: Sending to Joark using url= ${url}. " +
			"Archiving ${filListe.joinToString()}")

			val response = sendDataToJoark(request, url)
			val journalpostId = response?.journalpostId ?: "-1"

			logger.info("$key: Saved to Joark, got the following journalpostId: '$journalpostId'")
			return journalpostId

		} catch (e: Exception) {
			logger.error("$key: Error sending to Joark", e)
			throw ArchivingException(e)
		}
	}

	private fun sendDataToJoark(data: OpprettJournalpostRequest, url: String): OpprettJournalpostResponse? {
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_JSON
		val request = HttpEntity(data, headers)
		return restTemplate.postForObject(url, request, OpprettJournalpostResponse::class.java)
	}
}
