package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

object WireMockStubs {


	fun mockJoark(wireMock: WireMockServer, statusCode: Int, responseBody: String?, delay: Int = 0) {
		wireMock.stubFor(
			post(urlPathMatching("/rest/journalpostapi/v1/journalpost.*"))
				.willReturn(aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(responseBody)
					.withStatus(statusCode)
					.withFixedDelay(delay)))
	}

	fun mockSaf(wireMock: WireMockServer, statusCode: Int,  responseBody: String, delay: Int = 0) {
		wireMock.stubFor(
			post(urlEqualTo("/graphql"))
				.willReturn(
					aResponse()
						.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
						.withBody(responseBody)
						.withStatus(statusCode)
				)
		)
	}


	fun mockFileFetch(wireMock: WireMockServer, statusCode: Int, responseBody: String, delay: Int = 0) {
		wireMock.stubFor (
			get	(urlPathMatching("/innsendte/v1/files/[0-9a-fA-F-]{36}"))
				.willReturn(
					aResponse()
						.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
						.withBody(responseBody)
						.withStatus(statusCode)
						.withFixedDelay(delay)
				)
		)
	}


}
