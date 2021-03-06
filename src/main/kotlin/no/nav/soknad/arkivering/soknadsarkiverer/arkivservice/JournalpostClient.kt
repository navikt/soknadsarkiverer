package no.nav.soknad.arkivering.soknadsarkiverer.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class JournalpostClient(private val appConfiguration: AppConfiguration,
												@Qualifier("archiveWebClient") private val webClient: WebClient,
												private val metrics: ArchivingMetrics): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val SKIP_JOARK_IF_ENVIRONMENT: String = "prod"

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

			if (SKIP_JOARK_IF_ENVIRONMENT.equals(appConfiguration.config.profile, true) && isBeforeArchivingStart(soknadarkivschema.getInnsendtDato())) {
				val journalpostId = "-1"
				logger.info("$key: Skipped saving to Joark, fake the following journalpostId: '$journalpostId'")
				metrics.incJoarkSuccesses()
				return "-1"
			} else {
				val response = sendDataToJoark(request, url)
				val journalpostId = response?.journalpostId ?: "-1"

				logger.info("$key: Created journalpost for behandlingsId:'${soknadarkivschema.behandlingsid}', got the following journalpostId: '$journalpostId'")
				metrics.incJoarkSuccesses()
				return journalpostId
			}

		} catch (e: Exception) {
			metrics.incJoarkErrors()
			logger.error("$key: Error sending to Joark", e)
			throw ArchivingException(e)
		} finally {
			metrics.endTimer(timer)
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
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response -> response.bodyToMono(String::class.java).map { Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'") } })
			.bodyToMono(OpprettJournalpostResponse::class.java)
			.block()
	}


	private fun isBeforeArchivingStart(innsendtDato: Long): Boolean {
		val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
		logger.info("skip_archiving_until:  innsendtDato=${LocalDateTime.ofInstant(Instant.ofEpochSecond(innsendtDato), ZoneOffset.UTC)}, startArkivering=${appConfiguration.config.startArkivering}")
		return (LocalDateTime.ofInstant(Instant.ofEpochSecond(innsendtDato), ZoneOffset.UTC)).isBefore(LocalDateTime.parse(appConfiguration.config.startArkivering, formatter))
	}
}
