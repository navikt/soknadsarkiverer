package no.nav.soknad.arkivering.soknadsarkiverer

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer
import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import no.nav.soknad.arkivering.soknadsarkiverer.dto.Dokumenter
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilElementDto
import no.nav.soknad.arkivering.soknadsarkiverer.dto.JoarkResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.concurrent.TimeUnit


private lateinit var wiremockServer: WireMockServer
private lateinit var joarkUrl: String
private lateinit var filestorageUrl: String

fun setupMockedServices(port: Int, urlJoark: String, urlFilestorage: String) {
	joarkUrl = urlJoark
	filestorageUrl = urlFilestorage

	wiremockServer = WireMockServer(port)
	wiremockServer.start()
}

fun stopMockedServices() {
	wiremockServer.stop()
}

fun verifyMockedGetRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.GET)
fun verifyMockedPostRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.POST)

fun verifyMockedRequests(expectedCount: Int, url: String, requestMethod: RequestMethod) {
	val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, WireMock.urlMatching(url)).build()
	val startTime = System.currentTimeMillis()
	val timeout = 30 * 1000

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
	mockJoark(HttpStatus.OK.value(), createJoarkResponse())
}

fun mockJoarkIsWorkingButGivesInvalidResponse() {
	mockJoark(HttpStatus.OK.value(), "invalid response")
}

fun mockJoarkIsDown() {
	mockJoark(HttpStatus.NOT_FOUND.value(), "Mocked exception")
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
	wiremockServer.stubFor(
		WireMock.get(WireMock.urlMatching(filestorageUrl.replace("?", "\\?") + ".*"))
			.willReturn(WireMock.aResponse()
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(createFilestorageResponse(uuid))
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


fun setupMockedServices(port: Int, urlJoark: String, urlFilestorage: String,
												filestorageResponder: () -> ResponseMocker, joarkResponder: () -> ResponseMocker) {

	joarkUrl = urlJoark
	filestorageUrl = urlFilestorage

	val wiremockConfig = WireMockConfiguration()
		.port(port)
		.extensions(SoknadsarkivererResponseDefinitionTransformer(filestorageResponder, joarkResponder))
	wiremockServer = WireMockServer(wiremockConfig)
	wiremockServer.start()
}

fun createFilestorageResponse(uuid: String): String = ObjectMapper().writeValueAsString(listOf(FilElementDto(uuid, "apabepa".toByteArray())))

private fun createJoarkResponse(): String = ObjectMapper().writeValueAsString(
	JoarkResponse(listOf(Dokumenter("brevkode", "dokumentInfoId", "tittel")),
	"journalpostId", false, "journalstatus", "null"))


class SoknadsarkivererResponseDefinitionTransformer(private val filestorageResponder: () -> ResponseMocker,
																										private val joarkResponder: () -> ResponseMocker)
	: ResponseDefinitionTransformer() {

	override fun getName() = "SoknadsarkivererResponseDefinitionTransformer"

	override fun transform(request: Request, responseDefinition: ResponseDefinition, files: FileSource, parameters: Parameters): ResponseDefinition {

		val responseMocker = when {
			request.url.startsWith(filestorageUrl) -> filestorageResponder.invoke()
			request.url.startsWith(joarkUrl) -> joarkResponder.invoke()
			else -> ResponseMocker().withStatus(HttpStatus.OK)
		}

		return responseMocker.build()
	}
}

/**
 * This wrapper exists to avoid leaking Wiremock specifics into other classes
 */
class ResponseMocker {
	private val builder = ResponseDefinitionBuilder()

	fun withStatus(status: HttpStatus): ResponseMocker {
		builder.withStatus(status.value())
		return this
	}

	fun withBody(body: String): ResponseMocker {
		builder.withBody(body)
		return this
	}

	fun withHeader(key: String, value: String): ResponseMocker {
		builder.withHeader(key, value)
		return this
	}

	internal fun build(): ResponseDefinition = builder.build()
}
