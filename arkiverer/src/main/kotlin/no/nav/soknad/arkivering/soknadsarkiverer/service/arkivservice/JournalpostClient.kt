package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.service.ApplicationAlreadyArchivedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsmottaker.model.InnsendingTopicMsg
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.format.DateTimeFormatter

@Service
class JournalpostClient(@Value("\${joark.host}") private val joarkHost: String,
												@Value("\${joark.sendToJoark}") private val sendToJoark: Boolean,
												@Value("\${joark.journal-post}") private val journalPostUrl: String,
												@Qualifier("archiveRestClient") private val restClient: RestClient,
												private val metrics: ArchivingMetrics): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)
	private val secureLogsMarker: Marker = MarkerFactory.getMarker("TEAM_LOGS")


	override fun opprettJournalpost(key: String, soknadarkivschema: InnsendingTopicMsg, attachedFiles: List<FileInfo>): String {
		val timer = metrics.startJoarkLatency()
		try {
			logger.info("$key: About to create journalpost for behandlingsId: '${soknadarkivschema.innsendingsId}'")
			val request = createOpprettJournalpostRequest(soknadarkivschema, attachedFiles)

			val mainDocVariantFormats = soknadarkivschema.dokumenter.filter { it.erHovedskjema }.flatMap { it.varianter }.groupBy { it.filtype }
			if (mainDocVariantFormats.any { it.value.size > 1} ) {
				logger.warn("$key: Mottatte flere varianter av hovedskjema med samme variantfomat.  ${mainDocVariantFormats.filter { it.value.size > 1 }.map { it.key }}")
			}

			val response = sendDataToJoark(key, request, restClient, journalPostUrl)
			val journalpostId = response?.journalpostId ?: "-1"

			val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")

			val message = "$key: journalpostId:$journalpostId, innsendingsId:$key, skjemaNr:${soknadarkivschema.skjemanr}, " +
				"tema:${soknadarkivschema.arkivtema}, kanal:${soknadarkivschema.kanal}, " +
				"ettersendelsetilId:${soknadarkivschema.ettersendelseTilId}, " +
				"innsendtDato:${formatter.format(soknadarkivschema.innsendtDato)}"
			logger.info(message)
			logger.info(secureLogsMarker,
				"brukerId:${soknadarkivschema.brukerDto?.id}, " +
				"avsenderId:${soknadarkivschema.avsenderDto.id}, " +
				"avsenderNavn:${soknadarkivschema.avsenderDto.navn}, " +
				"$message")

			metrics.incJoarkSuccesses()
			if (!soknadarkivschema.innlogget) {
				metrics.incNoLoginJoarkSuccesses()
			}
			return journalpostId

		} catch (e: ApplicationAlreadyArchivedException) {
			logger.warn("$key: Application's eksternReferanseId ${soknadarkivschema.innsendingsId} already exists in the archive", e)
			throw e
		} catch (e: Exception) {
			metrics.incJoarkErrors()
			if (!soknadarkivschema.innlogget) {
				metrics.incNoLoginJoarkErrors()
			}
			val message = "$key: Error sending to Joark"
			logger.warn(message, e)
			throw ArchivingException(message, e)
		} finally {
			metrics.endTimer(timer)
		}
	}

	private fun sendDataToJoark(key: String, data: OpprettJournalpostRequest, client: RestClient, uri: String):
		OpprettJournalpostResponse? {

		if (!sendToJoark) {
			logger.info("$key: Feature flag is disabled - not sending to Joark.")
			return null
		}

		val method = HttpMethod.POST
		return client
			.method(method)
			.uri(uri)
			.contentType(APPLICATION_JSON)
			.accept(APPLICATION_JSON)
			.header("Nav-Callid", key)
			.body(data)
			.retrieve()
			.onStatus(HttpStatusCode::is4xxClientError ) { _, response ->
					val msg = "$key: Got ${response.statusCode} when requesting $method $uri"  + "Body response: ${response.body}"
					if (response.statusCode == HttpStatus.CONFLICT) {
						logger.warn(msg)
						throw ApplicationAlreadyArchivedException(msg)
					} else {
						logger.error("$key: Got ${response.statusCode} when requesting $method $uri")
						throw RuntimeException(msg)
					}
			}
			.onStatus(HttpStatusCode::is5xxServerError ) { _, response ->
					val msg = "$key: Got ${response.statusCode} when requesting $method $uri." + "Body response: ${response.body}"
					logger.error(msg)
					throw RuntimeException(msg)
			}
			.body(OpprettJournalpostResponse::class.java)
	}
}
