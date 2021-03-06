package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.Dokumenter
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType


const val filestorageContent = "filestoragecontent"
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

fun verifyMockedGetRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.GET)
fun verifyMockedPostRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.POST)
fun verifyMockedDeleteRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.DELETE)

private fun verifyMockedRequests(expectedCount: Int, url: String, requestMethod: RequestMethod) {

	val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, urlMatching(url)).build()
	val getCount = { wiremockServer.countRequestsMatching(requestPattern).count }

	loopAndVerify(expectedCount, getCount)
}

fun verifyPostRequest(url: String): List<LoggedRequest> = wiremockServer.findAll(postRequestedFor(urlMatching(url)))


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
			post(urlEqualTo(joarkUrl))
				.inScenario("integrationTest").whenScenarioStateIs(stateNames[attempt])
				.willReturn(aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody("Mocked exception, attempt $attempt")
					.withStatus(HttpStatus.NOT_FOUND.value()))
				.willSetStateTo(stateNames[attempt + 1]))
	}
	wiremockServer.stubFor(
		post(urlEqualTo(joarkUrl))
			.inScenario("integrationTest").whenScenarioStateIs(stateNames.last())
			.willReturn(aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createJoarkResponse())
				.withStatus(HttpStatus.OK.value())))
}

private fun mockJoark(statusCode: Int, responseBody: String, delay: Int) {
	wiremockServer.stubFor(
		post(urlEqualTo(joarkUrl))
			.willReturn(aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(responseBody)
				.withStatus(statusCode)
				.withFixedDelay(delay)))
}

fun mockFilestorageIsWorking(uuid: String) = mockFilestorageIsWorking(listOf(uuid to filestorageContent))

fun mockFilestorageIsWorking(uuidsAndResponses: List<Pair<String, String?>>) {
	val ids = uuidsAndResponses.joinToString(",") { it.first }
	mockFilestorageIsWorking(uuidsAndResponses, ids)
}

fun mockFilestorageIsWorking(uuidsAndResponses: List<Pair<String, String?>>, idsForUrl: String) {
	val urlPattern = urlMatching(filestorageUrl.replace("?", "\\?") + idsForUrl)

	wiremockServer.stubFor(
		get(urlPattern)
			.willReturn(aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createFilestorageResponse(uuidsAndResponses))
				.withStatus(HttpStatus.OK.value())))

	mockFilestoreageDeletionIsWorking(uuidsAndResponses.map { it.first })
}

fun mockFilestoreageDeletionIsWorking(uuids: List<String>) {
	val ids = uuids.joinToString(",")
	val urlPattern = urlMatching(filestorageUrl.replace("?", "\\?") + ids)

	wiremockServer.stubFor(
		delete(urlPattern)
			.willReturn(aResponse()
				.withStatus(HttpStatus.OK.value())))
}

fun mockFilestorageDeletionIsNotWorking() {
	wiremockServer.stubFor(
		delete(urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(aResponse()
				.withBody("Mocked exception for deletion")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}

fun mockFilestorageIsDown() {
	wiremockServer.stubFor(
		get(urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(aResponse()
				.withBody("Mocked exception for filestorage")
				.withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))
}

fun mockFilestoragePingIsWorking() {
	wiremockServer.stubFor(
		get("/internal/ping")
			.willReturn(aResponse()
				.withBody("pong")
				.withStatus(HttpStatus.OK.value())))
}

fun mockFilestoragePingIsNotWorking() {
	wiremockServer.stubFor(
		get("/internal/ping")
			.willReturn(aResponse()
				.withBody("Mocked ping error response for filestorage")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}

fun mockFilestorageIsReadyIsWorking() {
	wiremockServer.stubFor(
		get("/internal/isReady")
			.willReturn(aResponse()
				.withBody("Holla, si Ready")
				.withStatus(HttpStatus.OK.value())))
}

fun mockFilestorageIsReadyIsNotWorking() {
	wiremockServer.stubFor(
		get("/internal/isReady")
			.willReturn(aResponse()
				.withBody("Mocked isReady error response for filestorage")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}

fun mockJoarkIsAliveIsWorking() {
	wiremockServer.stubFor(
		get("/isAlive")
			.willReturn(aResponse()
				.withBody("Application is alive!")
				.withStatus(HttpStatus.OK.value())))
}

fun mockJoarkIsAliveIsNotWorking() {
	wiremockServer.stubFor(
		get("/isAlive")
			.willReturn(aResponse()
				.withBody("Mocked isAlive error response for filestorage")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}


private fun createFilestorageResponse(uuidsAndResponses: List<Pair<String, String?>>): String =
	ObjectMapper().writeValueAsString(
		uuidsAndResponses.map { (uuid, response) -> FilElementDto(uuid, response?.toByteArray()) }
	)

private fun createJoarkResponse(): String = ObjectMapper().writeValueAsString(
	OpprettJournalpostResponse(listOf(Dokumenter("brevkode", "dokumentInfoId", "tittel")),
		"journalpostId", false, "journalstatus", "null"))
