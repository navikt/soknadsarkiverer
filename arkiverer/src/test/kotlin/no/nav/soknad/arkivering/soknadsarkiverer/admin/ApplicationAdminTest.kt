package no.nav.soknad.arkivering.soknadsarkiverer.admin

import no.nav.soknad.arkivering.avroschemas.EventTypes.ARCHIVED
import no.nav.soknad.arkivering.avroschemas.EventTypes.FAILURE
import no.nav.soknad.arkivering.avroschemas.EventTypes.FINISHED
import no.nav.soknad.arkivering.avroschemas.EventTypes.RECEIVED
import no.nav.soknad.arkivering.avroschemas.EventTypes.STARTED
import no.nav.soknad.arkivering.soknadsarkiverer.utils.InnsendingTopicMsgBuilder
import no.nav.soknad.arkivering.soknadsarkiverer.utils.TestDokument
import no.nav.soknad.arkivering.soknadsarkiverer.utils.filestorageContent
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockFilestorageIsWorking
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockJoarkIsWorking
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockJoarkRespondsAfterAttempts
import no.nav.soknad.arkivering.soknadsarkiverer.utils.mockSafRequest_notFound
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import java.util.UUID
import no.nav.soknad.arkivering.soknadsarkiverer.utils.TokenGenerator
import no.nav.soknad.arkivering.soknadsarkiverer.utils.awaitFailureHandled
import no.nav.soknad.arkivering.soknadsarkiverer.utils.ArchivingFlowTest
import no.nav.soknad.arkivering.soknadsarkiverer.utils.awaitFinished
import no.nav.soknad.arkivering.soknadsarkiverer.utils.hasCount
import no.nav.soknad.arkivering.soknadsarkiverer.utils.readUnderLock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Duration
import java.time.Instant
import kotlin.collections.forEach

class ApplicationAdminTest : ArchivingFlowTest() {

	@Value("\${auth.issuers.azuread.issuer-uri}")
	private lateinit var azureadIssuer: String

	@Test
	fun `OpenAPI documents the admin API and Swagger UI is available`() {
		webTestClient.get()
			.uri("/v3/api-docs")
			.exchange()
			.expectStatus().isOk
			.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.openapi").exists()
			.jsonPath("$.paths['/admin/rerun/{key}']").exists()
			.jsonPath("$.components.schemas.ArchivingStatus.properties.key").exists()
			.jsonPath("$.components.schemas.ArchivingStatus.properties.key.default").doesNotExist()

		webTestClient.get()
			.uri("/swagger-ui/index.html")
			.exchange()
			.expectStatus().isOk
			.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String::class.java)
			.value { body -> assertTrue(body != null && body.contains("Swagger UI")) }
	}

	@Test
	fun `happy case - application is archieved in after retry`() {

		// Given
		val tasksGivenUpOnBefore = metrics.getTasksGivenUpOn()
		val innsendingsId = failArchiving()
		assertEquals(tasksGivenUpOnBefore + 1, metrics.getTasksGivenUpOn(), "Expected the task to be given up on")
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = innsendingsId)

		val mockJwt = createJwtToken()
		`when`(azureJwtDecoder.decode(anyString())).thenReturn(mockJwt.token)

		// When
		val response = webTestClient
			.mutate()
			.responseTimeout(Duration.ofMinutes(2))
			.build()

			.post()
			.uri { uriBuilder -> uriBuilder.path("/admin/rerun/$innsendingsId").build() }
			.headers { it.addAll(createHeaders()) }

			.exchange()
			.returnResult()

		// Then
		assert(response.status.is2xxSuccessful())
		kafkaListener.awaitFinished(innsendingsId)

		verifyProcessingEvents(
			innsendingsId, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts + 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 1
			)
		)
		assertEquals(tasksGivenUpOnBefore, metrics.getTasksGivenUpOn(), "Expected the rerun to clear the given-up task")

	}

	@Test
	fun `happy case - retry on archieved application is ignored`() {

		// Given
		val innsendingsId = successfullArchiving()

		val mockJwt = createJwtToken()
		`when`(azureJwtDecoder.decode(anyString())).thenReturn(mockJwt.token)

		// When
		val response = webTestClient
			.mutate()
			.responseTimeout(Duration.ofMinutes(2))
			.build()

			.post()
			.uri { uriBuilder -> uriBuilder.path("/admin/rerun/$innsendingsId").build() }
			.headers { it.addAll(createHeaders()) }

			.exchange()
			.returnResult()

		// Then
		assert(response.status.is2xxSuccessful())
		// The rerun publishes STARTED before it responds. All test topics have one partition, and one
		// stream thread handles processing events in order, so when a later application is FINISHED,
		// the application has handled that STARTED. Had it accepted the rerun, it would have created a task.
		successfullArchiving()
		assertEquals(null, taskListService.readUnderLock { getNumberOfAttempts(innsendingsId) }, "Expected no task for the ignored rerun")

		verifyProcessingEvents(
			innsendingsId, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount 2,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)

	}

	@Test
	fun `fail case - call without token failes`() {

		// Given
		val innsendingsId = UUID.randomUUID().toString()

		val mockJwt = null
		`when`(azureJwtDecoder.decode(anyString())).thenReturn(mockJwt)

		// When
		val response = webTestClient
			.mutate()
			.responseTimeout(Duration.ofMinutes(2))
			.build()

			.post()
			.uri { uriBuilder -> uriBuilder.path("/admin/rerun/$innsendingsId").build() }
			.headers { it.addAll(createHeaders(issuer = null)) }

			.exchange()
			.returnResult()

		// Then
		assert(response.status == HttpStatus.UNAUTHORIZED)

	}

	@Test
	fun `fail case - call with wrong issuer failes`() {

		// Given
		val innsendingsId = UUID.randomUUID().toString()

		val mockJwt = createJwtToken(issuer = "http://localhost/tokenx")
		`when`(azureJwtDecoder.decode(anyString())).thenReturn(mockJwt.token)

		// When
		val response = webTestClient
			.mutate()
			.responseTimeout(Duration.ofMinutes(2))
			.build()

			.post()
			.uri { uriBuilder -> uriBuilder.path("/admin/rerun/$innsendingsId").build() }
			.headers { it.addAll(createHeaders(issuer = "tokenx")) }

			.exchange()
			.returnResult()

		// Then
		assert(response.status == HttpStatus.UNAUTHORIZED)

	}

	private fun failArchiving(): String {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkRespondsAfterAttempts(maxNumberOfAttempts)
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFailureHandled(key, taskListService)

		verifyProcessingEvents(
			loggedInMsg.innsendingsId, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount maxNumberOfAttempts,
				ARCHIVED hasCount 0,
				FINISHED hasCount 0,
				FAILURE hasCount 1
			)
		)

		return key
	}

	private fun successfullArchiving(): String {
		val key = UUID.randomUUID().toString()
		val fileIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
		val loggedInMsg = InnsendingTopicMsgBuilder()
			.withInnsendingsId(key)
			.withTittel("Test dokument")
			.withKanal("NAV_NO")
			.withTestDokumenter(mutableListOf(
				TestDokument("NAV 11-12.12", true, tittel = "Test dokument", fileIds),
			))
			.build()

		mockFilestorageIsWorking(fileIds.map{it to filestorageContent})
		mockJoarkIsWorking()
		mockSafRequest_notFound(innsendingsId = key)

		putDataOnKafkaTopic(loggedInMsg)
		kafkaListener.awaitFinished(key)

		verifyProcessingEvents(
			loggedInMsg.innsendingsId, mapOf(
				RECEIVED hasCount 1,
				STARTED hasCount 1,
				ARCHIVED hasCount 1,
				FINISHED hasCount 1,
				FAILURE hasCount 0
			)
		)
		return key
	}

	private fun createHeaders(issuer: String? = "azuread", audience: String? = null, map: Map<String, String>? = mapOf()): HttpHeaders {
		val token = when {
			issuer == null -> null
			issuer == "azuread" -> TokenGenerator(mockOAuth2Server).lagAzureADToken(audience_ = audience)
			issuer == "tokenx" -> TokenGenerator(mockOAuth2Server).lagTokenxToken(audience_ = audience)
			else -> null
		}
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_JSON
		if (token != null) 	headers.add(HttpHeaders.AUTHORIZATION, "$BEARER$token")
		map?.forEach { (headerName, headerValue) -> headers.add(headerName, headerValue) }
		return headers
	}

	private val BEARER = "Bearer "

	private fun createJwtToken(issuer: String? = azureadIssuer): JwtAuthenticationToken {
		val now = Instant.now()
		val jwt = Jwt.withTokenValue("local-auth-disabled")
			.header("alg", "none")
			.subject("local-dev-user")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(3600))
			.claim("aud", "local-dev")
			.claim("NAVident", "A123456")
			.claim("preferred_username", "local-dev@example.com")
			.claim("scp", "defaultaccess serviceklage-klassifisering")
			.apply { issuer?.let { claim("iss", it) } }
			.build()

		return JwtAuthenticationToken(jwt)
	}

}
