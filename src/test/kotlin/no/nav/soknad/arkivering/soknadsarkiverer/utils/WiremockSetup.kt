package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import no.nav.soknad.arkivering.soknadsarkiverer.dto.Dokumenter
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType


private lateinit var wiremockServer: WireMockServer
private lateinit var joarkUrl: String
private lateinit var filestorageUrl: String

fun setupMockedNetworkServices(port: Int, urlJoark: String, urlFilestorage: String) {
	joarkUrl = urlJoark
	filestorageUrl = urlFilestorage

	wiremockServer = WireMockServer(port)
	wiremockServer.start()
}

fun stopMockedNetworkServices() {
	wiremockServer.stop()
}

fun verifyMockedPostRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.POST)
fun verifyMockedDeleteRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.DELETE)

private fun verifyMockedRequests(expectedCount: Int, url: String, requestMethod: RequestMethod) {

	val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, WireMock.urlMatching(url)).build()
	val getCount = { wiremockServer.countRequestsMatching(requestPattern).count }

	loopAndVerify(expectedCount, getCount)
}

fun mockJoarkIsWorking() {
	mockJoark(HttpStatus.OK.value(), createJoarkResponse())
}

fun mockJoarkIsWorkingButGivesInvalidResponse() {
	mockJoark(HttpStatus.OK.value(), "invalid_response")
}

fun mockJoarkIsDown() {
	mockJoark(HttpStatus.NOT_FOUND.value(), "Mocked_exception")
}

fun mockJoarkRespondsAfterAttempts(attempts: Int) {

	val stateNames = listOf(Scenario.STARTED).plus((0 until attempts).map { "iteration_$it" })
	for (attempt in (0 until stateNames.size - 1)) {
		wiremockServer.stubFor(
			WireMock.post(WireMock.urlEqualTo(joarkUrl))
				.inScenario("integrationTest").whenScenarioStateIs(stateNames[attempt])
				.willReturn(WireMock.aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody("Mocked exception")
					.withStatus(HttpStatus.NOT_FOUND.value()))
				.willSetStateTo(stateNames[attempt + 1]))
	}
	wiremockServer.stubFor(
		WireMock.post(WireMock.urlEqualTo(joarkUrl))
			.inScenario("integrationTest").whenScenarioStateIs(stateNames.last())
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createJoarkResponse())
				.withStatus(HttpStatus.OK.value())))
}

private fun mockJoark(statusCode: Int, responseBody: String) {
	wiremockServer.stubFor(
		WireMock.post(WireMock.urlEqualTo(joarkUrl))
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(responseBody)
				.withStatus(statusCode)))
}

fun mockFilestorageIsWorking(uuid: String) {
	val urlPattern = WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*")

	wiremockServer.stubFor(
		WireMock.get(urlPattern)
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createFilestorageResponse(uuid))
				.withStatus(HttpStatus.OK.value())))

	wiremockServer.stubFor(
		WireMock.delete(urlPattern)
			.willReturn(WireMock.aResponse()
				.withStatus(HttpStatus.OK.value())))
}

fun mockFilestorageDeletionIsNotWorking() {
	wiremockServer.stubFor(
		WireMock.delete(WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(WireMock.aResponse()
				.withBody("Mocked exception")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}

fun mockFilestorageIsDown() {
	wiremockServer.stubFor(
		WireMock.get(WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(WireMock.aResponse()
				.withBody("Mocked exception")
				.withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))
}

fun createFilestorageResponse(uuid: String): String = ObjectMapper().writeValueAsString(listOf(FilElementDto(uuid, "apabepa".toByteArray())))

private fun createJoarkResponse(): String = ObjectMapper().writeValueAsString(
	JoarkResponse(listOf(Dokumenter("brevkode", "dokumentInfoId", "tittel")),
		"journalpostId", false, "journalstatus", "null"))
