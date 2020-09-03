package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.Dokumenter
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostResponse
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

fun mockJoarkIsWorking(delay: Int = 0) {
	mockJoark(HttpStatus.OK.value(), createJoarkResponse(), delay)
}

fun mockJoarkIsWorkingButGivesInvalidResponse(delay: Int = 0) {
	mockJoark(HttpStatus.OK.value(), "mocked_invalid_response", delay)
}

fun mockJoarkIsDown(delay: Int = 0) {
	mockJoark(HttpStatus.NOT_FOUND.value(), "Mocked_exception", delay)
}

fun mockJoarkRespondsAfterAttempts(attempts: Int) {

	val stateNames = listOf(Scenario.STARTED).plus((0 until attempts).map { "iteration_$it" })
	for (attempt in (0 until stateNames.size - 1)) {
		wiremockServer.stubFor(
			WireMock.post(WireMock.urlEqualTo(joarkUrl))
				.inScenario("integrationTest").whenScenarioStateIs(stateNames[attempt])
				.willReturn(WireMock.aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody("Mocked exception, attempt $attempt")
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

private fun mockJoark(statusCode: Int, responseBody: String, delay: Int) {
	wiremockServer.stubFor(
		WireMock.post(WireMock.urlEqualTo(joarkUrl))
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(responseBody)
				.withStatus(statusCode)
				.withFixedDelay(delay)))
}

fun mockFilestorageIsWorking(uuid: String) = mockFilestorageIsWorking(listOf(uuid to "apabepa"))

fun mockFilestorageIsWorking(uuidsAndResponses: List<Pair<String, String?>>) {
	val urlPattern = WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*")

	wiremockServer.stubFor(
		WireMock.get(urlPattern)
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createFilestorageResponse(uuidsAndResponses))
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
				.withBody("Mocked exception for deletion")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}

fun mockFilestorageIsDown() {
	wiremockServer.stubFor(
		WireMock.get(WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(WireMock.aResponse()
				.withBody("Mocked exception for filestorage")
				.withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))
}

private fun createFilestorageResponse(uuidsAndResponses: List<Pair<String, String?>>): String =
	ObjectMapper().writeValueAsString(
		uuidsAndResponses.map { (uuid, response) -> FilElementDto(uuid, response?.toByteArray()) }
	)

private fun createJoarkResponse(): String = ObjectMapper().writeValueAsString(
	OpprettJournalpostResponse(listOf(Dokumenter("brevkode", "dokumentInfoId", "tittel")),
		"journalpostId", false, "journalstatus", "null"))
