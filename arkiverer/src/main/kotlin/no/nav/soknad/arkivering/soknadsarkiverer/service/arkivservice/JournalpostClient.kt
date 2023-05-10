package no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice

import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.service.ApplicationAlreadyArchivedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.converter.createOpprettJournalpostRequest
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient

@Service
class JournalpostClient(@Value("\${joark.host}") private val joarkHost: String,
												@Value("\${joark.sendToJoark}") private val sendToJoark: Boolean,
												@Value("\${joark.journal-post}") private val journalPostUrl: String,
												@Qualifier("archiveWebClient") private val webClient: WebClient,
												private val metrics: ArchivingMetrics): JournalpostClientInterface {

	private val logger = LoggerFactory.getLogger(javaClass)

	val bidClient: WebClient = webClient.mutate().defaultHeader("Nav-Consumer-Id", "dialogstyring-bidrag").build()

	override fun opprettJournalpost(key: String, soknadarkivschema: Soknadarkivschema, attachedFiles: List<FileData>): String {
		val timer = metrics.joarkLatencyStart()
		try {
			logger.info("$key: About to create journalpost for behandlingsId: '${soknadarkivschema.behandlingsid}'")
			val request = createOpprettJournalpostRequest(soknadarkivschema, attachedFiles)

			val mainDocVariantFormats = soknadarkivschema.mottatteDokumenter.filter { it.erHovedskjema }.flatMap { it.mottatteVarianter }.groupBy { it.variantformat }
			if (mainDocVariantFormats.any { it.value.size > 1} ) {
				logger.warn("$key: Mottatte flere varianter av hovedskjema med samme variantfomat.  ${mainDocVariantFormats.filter { it.value.size > 1 }.map { it.key }}")
			}

			val client = if (soknadarkivschema.arkivtema == "BID") bidClient else webClient
			val response = sendDataToJoark(key, request, client, joarkHost + journalPostUrl)
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
			val message = "$key: Error sending to Joark"
			logger.warn(message, e)
			throw ArchivingException(message, e)
		} finally {
			metrics.endTimer(timer)
		}
	}

	private fun sendDataToJoark(key: String, data: OpprettJournalpostRequest, client: WebClient, uri: String):
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
