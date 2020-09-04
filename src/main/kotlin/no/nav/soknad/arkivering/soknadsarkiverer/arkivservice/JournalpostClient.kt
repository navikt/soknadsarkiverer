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
import org.springframework.web.client.RestTemplate

//@Service
class JournalpostClient(private val appConfiguration: AppConfiguration,
												@Qualifier("archiveRestTemplate") private val restTemplate: RestTemplate): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun ping(): String {
		return restTemplate.getForObject("${appConfiguration.config.joarkUrl}/ping", String::class.java)!!
	}

	override fun opprettjournalpost(key: String, applicationMessage: Soknadarkivschema, attachedFiles: List<FilElementDto>): String {
		logger.info("${key}: Skal opprette journalpost for søknad med behandlingsId=${applicationMessage.getBehandlingsid()}")
		val opprettJounalpostRequest: OpprettJournalpostRequest = createOpprettJournalpostRequest(applicationMessage, attachedFiles)

		val url = appConfiguration.config.joarkHost + appConfiguration.config.joarkUrl

		val responseEntity = restTemplate.postForEntity(url, HttpEntity<Any>(opprettJounalpostRequest), OpprettJournalpostResponse::class.java)

		if (responseEntity != null) {
			if (responseEntity.body != null) {
				val journalpostId = responseEntity.body?.journalpostId ?: "-1"
				logger.info("${key}: Opprettet journalpost med id=${journalpostId} for søknad med behandlingsId=${applicationMessage.getBehandlingsid()}.")
				return journalpostId
			}
			throw ArchivingException(RuntimeException("${key}: Feil ved forsøk på å opprette journalpost for ${applicationMessage.getBehandlingsid()}." +
				" Response status = ${responseEntity.statusCodeValue}"))
		} else {
			throw ArchivingException(RuntimeException("${key}: Feil ved forsøk på å opprette journalpost for ${applicationMessage.getBehandlingsid()}"))
		}

	}

}
