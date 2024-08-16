package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.expediagroup.graphql.client.types.GraphQLClientSourceLocation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import no.nav.soknad.arkiverer.saf.generated.HentJournalpostGittEksternReferanseId
import no.nav.soknad.arkiverer.saf.generated.enums.*
import no.nav.soknad.arkiverer.saf.generated.hentjournalpostgitteksternreferanseid.AvsenderMottaker
import no.nav.soknad.arkiverer.saf.generated.hentjournalpostgitteksternreferanseid.Bruker
import no.nav.soknad.arkiverer.saf.generated.hentjournalpostgitteksternreferanseid.Journalpost
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.Dokumenter
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.api.OpprettJournalpostResponse
import no.nav.soknad.innsending.model.SoknadFile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*


const val filestorageContent = "filestoragecontent"
private lateinit var wiremockServer: WireMockServer
private lateinit var joarkUrl: String
private lateinit var innsendingApiPath: String
private lateinit var safUrl: String

fun setupMockedNetworkServices(port: Int, urlJoark: String, pathInnsendingApi: String, urlSaf: String) {
	joarkUrl = urlJoark
	innsendingApiPath = pathInnsendingApi
	safUrl = urlSaf

	wiremockServer = WireMockServer(port)
	wiremockServer.start()
}

fun stopMockedNetworkServices() {
	wiremockServer.stop()
}

fun verifyMockedGetRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.GET)
fun verifyMockedPostRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.POST)
fun verifyMockedDeleteRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.DELETE)

fun countRequests(url: String, requestMethod: RequestMethod): Int {
	val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, urlMatching(url)).build()
	return wiremockServer.countRequestsMatching(requestPattern).count
}

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

fun mockAlreadyArchivedResponse(delay: Int = 1) {
	mockJoark(HttpStatus.CONFLICT.value(), "Already archived", delay)
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

fun mockFilestorageIsWorking(id: String) = mockFilestorageIsWorking(listOf(id to filestorageContent))

fun mockFilestorageIsWorking(idsAndResponses: List<Pair<String, String?>>) {

	idsAndResponses.forEach { (id, content) ->
		val urlPattern = "$innsendingApiPath/$id"

		val response = createInnsendingApiResponse(Triple(id, content, SoknadFile.FileStatus.ok))
		mockInnsendingApiGetRequest(urlPattern, response)
	}

	mockFilestorageDeletionIsWorking(idsAndResponses.map { it.first })
}

fun mockRequestedFileIsGone() {
	val response = createInnsendingApiResponse(Triple(UUID.randomUUID().toString(), null, SoknadFile.FileStatus.deleted))
	mockInnsendingApiGetRequest("$innsendingApiPath/.*", response)
}

fun mockRequestedFileIsNotFound() {
	val response = createInnsendingApiResponse(Triple(UUID.randomUUID().toString(), null, SoknadFile.FileStatus.notfound))
	mockInnsendingApiGetRequest("$innsendingApiPath/.*", response)
}

private fun mockInnsendingApiGetRequest(url: String, response: String) {
	wiremockServer.stubFor(
		get(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(response)
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockFilestorageDeletionIsWorking(uuids: List<String>) {
	val ids = uuids.joinToString(",")
	val urlPattern = urlMatching(innsendingApiPath + ids)

	wiremockServer.stubFor(
		delete(urlPattern)
			.willReturn(aResponse()
				.withStatus(HttpStatus.OK.value())))
}

fun mockFilestorageDeletionIsNotWorking() {
	wiremockServer.stubFor(
		delete(urlMatching("$innsendingApiPath.*"))
			.willReturn(aResponse()
				.withBody("Mocked exception for deletion")
				.withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))
}

fun mockFilestorageIsDown() {
	wiremockServer.stubFor(
		get(urlMatching("$innsendingApiPath.*"))
			.willReturn(aResponse()
				.withBody("Mocked exception for filestorage")
				.withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))
}

fun mockFilestoragePingIsWorking() {
	wiremockServer.stubFor(
		get("/health/ping")
			.willReturn(aResponse()
				.withStatus(HttpStatus.OK.value())))
}

private fun createInnsendingApiResponse(idAndResponseAndStatus: Triple<String, String?, SoknadFile.FileStatus>): String {
	val (id, response, status) = idAndResponseAndStatus
	val createdAt = OffsetDateTime.now(ZoneOffset.UTC)
	return ObjectMapper()
		.registerModule(JavaTimeModule())
		.writeValueAsString(listOf(SoknadFile(id, status, response?.toByteArray(), createdAt)))
}

private fun createJoarkResponse(): String = ObjectMapper().writeValueAsString(
	OpprettJournalpostResponse(listOf(Dokumenter("brevkode", "dokumentInfoId", "tittel")),
		"journalpostId", false, "journalstatus", "null"))

fun mockSafRequest_notFound(url: String? = safUrl, innsendingsId: String) {
	wiremockServer.stubFor(
		post(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withoutJournalpost(innsendingsId = innsendingsId))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_found(url: String? = safUrl, innsendingsId: String) {
	wiremockServer.stubFor(
		post(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withJournalpost(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_error(url: String? = safUrl, innsendingsId: String) {
	wiremockServer.stubFor(
		post(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withError(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_foundAfterAttempt(url: String? = safUrl, innsendingsId: String, attempts: Int) {
	val stateNames = listOf(Scenario.STARTED).plus((0 until attempts).map { "iteration_$it" })
	for (attempt in (0 until stateNames.size - 1)) {
		wiremockServer.stubFor(
			post(urlMatching(url))
				.inScenario("integrationTest").whenScenarioStateIs(stateNames[attempt])
				.willReturn(
					aResponse()
						.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
						.withBody(createSafResponse_withoutJournalpost(innsendingsId = innsendingsId))
						.withStatus(HttpStatus.OK.value())
				)
		)
	}
	wiremockServer.stubFor(
		post(urlMatching(url))
			.inScenario("integrationTest").whenScenarioStateIs(stateNames.last())
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withJournalpost(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_foundAfterAttempt_ApplicationTest(url: String? = safUrl, innsendingsId: String, attempts: Int) {
	val stateNames = listOf(Scenario.STARTED).plus((0 until attempts).map { "iteration_$it" })
		wiremockServer.stubFor(
			post(urlMatching(url))
				.inScenario("applicationTest")
				.whenScenarioStateIs(stateNames[0])
				.willReturn(
					aResponse()
						.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
						.withBody(createSafResponse_withoutJournalpost(innsendingsId = innsendingsId))
						.withStatus(HttpStatus.OK.value())
				)
				.willSetStateTo(stateNames.last())
		)
	wiremockServer.stubFor(
		post(urlMatching(url))
			.inScenario("applicationTest")
			.whenScenarioStateIs(stateNames.last())
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withJournalpost(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}
private fun createSafResponse_withJournalpost(innsendingsId: String): String {
	val objectMapper = ObjectMapper()
	return objectMapper.writeValueAsString(
		graphQlResponse(data = createSafJournalpostResponse(innsendingsId), errors = null, extensions = null)
	)
}

private fun createSafResponse_withoutJournalpost(innsendingsId: String): String
{
	val objectMapper = ObjectMapper()
	return objectMapper.writeValueAsString(
		graphQlResponse(data = null, errors = null, extensions = null))
}

private fun createSafResponse_withError(innsendingsId: String): String =  ObjectMapper().writeValueAsString(
	graphQlResponse(data = null, errors = createSafErrorResponse(innsendingsId), extensions = null)
)

private fun createSafJournalpostResponse(innsendingsId: String) =
	HentJournalpostGittEksternReferanseId.Result(
		Journalpost(
			journalpostId = "12345", tittel = "Test søknad",
			journalposttype = Journalposttype.I, journalstatus = Journalstatus.MOTTATT,
			tema = Tema.BID, bruker = Bruker("12345678901", BrukerIdType.FNR),
			avsenderMottaker = AvsenderMottaker("12345678901", type = AvsenderMottakerIdType.FNR),
			datoOpprettet = LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
			eksternReferanseId = innsendingsId
		)
	)

private fun createSafErrorResponse(innsendingsId: String) =
	listOf(GraphQLErrorResponse(message = "401 Unauthorized. Exception when looking for $innsendingsId"))

data class graphQlResponse<Journalpost> (
	override val data: Journalpost? = null,
	override val errors: List<GraphQLClientError>? = null,
	override val extensions: Map<String, Any?>? = null
): GraphQLClientResponse<Journalpost>

data class GraphQLErrorResponse (
	override val extensions: Map<String, Any?>? = null,
	override val locations: List<GraphQLClientSourceLocation>? = null,
	override val message: String,
	override val path: List<Any>? = null
): GraphQLClientError


