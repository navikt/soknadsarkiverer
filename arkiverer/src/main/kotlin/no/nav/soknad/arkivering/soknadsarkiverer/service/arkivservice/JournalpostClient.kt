package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.service.ApplicationAlreadyArchivedException
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
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
												@Qualifier("archiveWebClient") private val webClient: WebClient,
												private val metrics: ArchivingMetrics): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	val bidClient: WebClient = webClient.mutate().defaultHeader("Nav-Consumer-Id", "dialogstyring-bidrag" ).build()

	override fun isAlive(): String {
		return webClient
			.get()
			.uri(appConfiguration.config.joarkHost + "/isAlive")
			.retrieve()
			.bodyToMono(String::class.java)
			.block()!!
	}

	override fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FilElementDto>): String {
		val timer = metrics.joarkLatencyStart()
		try {
			logger.info("$key: About to create journalpost for behandlingsId: '${soknadarkivschema.behandlingsid}'")
			val request: OpprettJournalpostRequest = createOpprettJournalpostRequest(soknadarkivschema, attachedFiles)

			val url = appConfiguration.config.joarkHost + appConfiguration.config.joarkUrl
			val client = if (soknadarkivschema.arkivtema == "BID") bidClient else webClient
			val response = sendDataToJoark(client, request, url)
			val journalpostId = response?.journalpostId ?: "-1"

			logger.info("$key: Created journalpost for behandlingsId:'${soknadarkivschema.behandlingsid}', " +
				"got the following journalpostId: '$journalpostId'")
			metrics.incJoarkSuccesses()
			return journalpostId

		} catch (e: ApplicationAlreadyArchivedException) {
			logger.warn("$key: Application's eksternReferanseId ${soknadarkivschema.behandlingsid} already exists in the archive", e)
			throw e
		} catch (e: Exception) {
			metrics.incJoarkErrors()
			logger.error("$key: Error sending to Joark", e)
			throw ArchivingException(e)
		} finally {
			metrics.endTimer(timer)
		}
	}

	private fun sendDataToJoark(client: WebClient, data: OpprettJournalpostRequest, uri: String):
		OpprettJournalpostResponse? {

		val method = HttpMethod.POST
		return client
			.method(method)
			.uri(uri)
			.contentType(APPLICATION_JSON)
			.accept(APPLICATION_JSON)
			.body(BodyInserters.fromValue(data))
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response -> response.bodyToMono(String::class.java).map {
					if (response.statusCode() == HttpStatus.CONFLICT) {
						ApplicationAlreadyArchivedException("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'")
					} else {
						Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'")
					}
				}
				})
			.bodyToMono(OpprettJournalpostResponse::class.java)
			.block()
	}
}
