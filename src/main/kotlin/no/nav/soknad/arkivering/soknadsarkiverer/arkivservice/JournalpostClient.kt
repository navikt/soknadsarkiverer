package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.Metrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient

@Service
class JournalpostClient(private val appConfiguration: AppConfiguration,
												@Qualifier("archiveWebClient") private val webClient: WebClient): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val SKIP_JOARK_IF_ENVIRONMENT: String = "prod"

	override fun ping(): String {
		return webClient
			.get()
			.uri(appConfiguration.config.joarkHost + "/isAlive")
			.retrieve()
			.bodyToMono(String::class.java)
			.block()!!
	}

	override fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FilElementDto>): String {
		val timer = Metrics.joarkLatencyStart()
		try {
			logger.info("$key: Creating journalpost with behandlingsId=${soknadarkivschema.getBehandlingsid()}")
			val request: OpprettJournalpostRequest = createOpprettJournalpostRequest(soknadarkivschema, attachedFiles)

			val url = appConfiguration.config.joarkHost + appConfiguration.config.joarkUrl

			if (SKIP_JOARK_IF_ENVIRONMENT.equals(appConfiguration.config.profile, true)) {
				val journalpostId = "-1"
				logger.info("$key: Skipped saving to Joark, fake the following journalpostId: '$journalpostId'")
				Metrics.incJoarkSuccesses()
				return "-1"
			} else {
				val response = sendDataToJoark(request, url)
				val journalpostId = response?.journalpostId ?: "-1"

				logger.info("$key: Saved to Joark, got the following journalpostId: '$journalpostId'")
				Metrics.incJoarkSuccesses()
				return journalpostId
			}

		} catch (e: Exception) {
			Metrics.incJoarkErrors()
			logger.error("$key: Error sending to Joark", e)
			throw ArchivingException(e)
		} finally {
		    Metrics.endTimer(timer)
		}
	}

	private fun sendDataToJoark(data: OpprettJournalpostRequest, uri: String): OpprettJournalpostResponse? {
		val method = HttpMethod.POST
		return webClient
			.method(method)
			.uri(uri)
			.contentType(APPLICATION_JSON)
			.accept(APPLICATION_JSON)
			.body(BodyInserters.fromValue(data))
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus != HttpStatus.OK },
				{ response -> response.bodyToMono(String::class.java).map { Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'") } })
			.bodyToMono(OpprettJournalpostResponse::class.java)
			.block()
	}
}
