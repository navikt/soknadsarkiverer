package no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi.api.opprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

class JoarkArchiverMockClient(@Qualifier("createRestTemplate") private val restTemplate: RestTemplate,
															private val appConfiguration: AppConfiguration): JournalpostClientInterface {
	private val logger = LoggerFactory.getLogger(javaClass)

	override fun ping(): String {
		TODO("Not yet implemented")
	}

	override fun opprettjournalpost(applicationMessage: Soknadarkivschema, attachedFiles: List<FilElementDto>): String {
		val opprettJounalpostRequest: OpprettJournalpostRequest = createOpprettJournalpostRequest(applicationMessage, attachedFiles)
		val url = appConfiguration.config.joarkHost + appConfiguration.config.joarkUrl

		val responseEntity = restTemplate.postForEntity(url, HttpEntity<Any>(opprettJounalpostRequest), opprettJournalpostResponse::class.java)

		if (responseEntity != null) {
			if (responseEntity.body != null) {
				return responseEntity.body?.journalpostId ?: "-1"
			}
			throw RuntimeException("Feil ved forsøk på å opprette journalpost for ${applicationMessage.getBehandlingsid()}." +
				" Response status = ${responseEntity.statusCodeValue}")
		} else {
			throw RuntimeException("Feil ved forsøk på å opprette journalpost for ${applicationMessage.getBehandlingsid()}")
		}
	}
}
