package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.soknad.innsending.model.SoknadFile
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class FetchFileApi(@Qualifier("innsendingApiRestClient")private val client: RestClient) {

	private val logger = LoggerFactory.getLogger(javaClass)

	fun hentInnsendteFiler(uuids: List<String>, xInnsendingId: String): List<SoknadFile> {

		val uri = "/innsendte/v1/files/${uuids.joinToString(",")}"

		val method = HttpMethod.GET
		return client
			.method(method)
			.uri(uri)
			.contentType(APPLICATION_JSON)
			.accept(APPLICATION_JSON)
			.header("x-innsendingId", xInnsendingId)
			.header("Nav-Callid", xInnsendingId)
			.retrieve()
			.onStatus(HttpStatusCode::is4xxClientError) { _, response ->
				val msg =
					"$xInnsendingId: Got ${response.statusCode} when requesting $method $uri" + "Body response: ${response.body}"
				logger.error("$xInnsendingId: Got ${response.statusCode} when requesting $method $uri")
				throw RuntimeException(msg)
			}
			.onStatus(HttpStatusCode::is5xxServerError) { _, response ->
				val msg =
					"$xInnsendingId: Got ${response.statusCode} when requesting $method $uri." + "Body response: ${response.body}"
				logger.error(msg)
				throw RuntimeException(msg)
			}
			.body(object : ParameterizedTypeReference<List<SoknadFile>>() {})
			?: throw RuntimeException("$xInnsendingId: requesting attachments ${uuids.joinToString(",")} failed")

	}


}
