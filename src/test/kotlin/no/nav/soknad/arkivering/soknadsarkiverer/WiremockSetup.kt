package no.nav.soknad.arkivering.soknadsarkiverer

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.concurrent.TimeUnit


private lateinit var wiremockServer: WireMockServer
private lateinit var joarkUrl: String
private lateinit var filestorageUrl: String

fun setupMockedServices(port: Int, urlJoark: String, urlFilestorage: String) {
	joarkUrl = urlJoark
	filestorageUrl =  urlFilestorage

	wiremockServer = WireMockServer(port)
	wiremockServer.start()
}

fun stopMockedServices() {
	wiremockServer.stop()
}

fun verifyMockedPostRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.POST)

fun verifyMockedRequests(expectedCount: Int, url: String, requestMethod: RequestMethod) {
	val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, WireMock.urlMatching(url)).build()
	val startTime = System.currentTimeMillis()
	val timeout = 30*1000

	while (System.currentTimeMillis() < startTime + timeout) {
		val matches = wiremockServer.countRequestsMatching(requestPattern)

		if (matches.count == expectedCount) {
			break
		}
		TimeUnit.MILLISECONDS.sleep(50)
	}
	assertEquals(expectedCount, wiremockServer.countRequestsMatching(requestPattern).count)
}

fun mockJoarkIsWorking() {
	mockJoark(HttpStatus.OK.value())
}

fun mockJoarkIsDown() {
	mockJoark(HttpStatus.NOT_FOUND.value())
}

fun mockJoarkRespondsAfterAttempts(attempts: Int) {

	val stateNames = listOf(Scenario.STARTED).plus ((0 until attempts).map { "iteration_$it" })
	for (attempt in (0 until stateNames.size - 1)) {
		wiremockServer.stubFor(
			WireMock.post(WireMock.urlEqualTo(joarkUrl))
				.inScenario("integrationTest").whenScenarioStateIs(stateNames[attempt])
				.willReturn(WireMock.aResponse().withStatus(HttpStatus.NOT_FOUND.value()))
				.willSetStateTo(stateNames[attempt + 1]))
	}
	wiremockServer.stubFor(
		WireMock.post(WireMock.urlEqualTo(joarkUrl))
			.inScenario("integrationTest").whenScenarioStateIs(stateNames.last())
			.willReturn(WireMock.aResponse().withStatus(HttpStatus.OK.value())))
}

fun mockJoark(statusCode: Int) {
	wiremockServer.stubFor(
		WireMock.post(WireMock.urlEqualTo(joarkUrl))
			.willReturn(WireMock.aResponse().withStatus(statusCode)))
}

fun mockFilestorageIsWorking() {
	wiremockServer.stubFor(
		WireMock.get(WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
				.withBody("apabepa".toByteArray())
				.withStatus(HttpStatus.OK.value())))
}

fun mockFilestorageIsDown() {
	wiremockServer.stubFor(
		WireMock.get(WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(WireMock.aResponse()
				.withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))
}
