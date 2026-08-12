package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.nimbusds.jose.JOSEObjectType
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback


class TokenGenerator(
	private val mockOAuth2Server: MockOAuth2Server,
) {
	companion object {
		val subject = "12345678901"
	}

	private val azuread = "azuread"
	private val audience = "aud-localhost"
	private val expiry = 2 * 3600L

	fun lagAzureADToken(fnr: String? = null, audience_: String?): String {
		val pid = fnr ?: subject
		val aud = audience_ ?: audience
		return mockOAuth2Server.issueToken(
			issuerId = azuread,
			clientId = "application",
			tokenCallback = DefaultOAuth2TokenCallback(
				issuerId = azuread,
				subject = pid,
				typeHeader = JOSEObjectType.JWT.type,
				claims = mapOf("aud" to listOf(aud), "pid" to pid),
				expiry = expiry
			)
		).serialize()
	}

	fun lagTokenxToken(fnr: String? = null, audience_: String?): String {
		val pid = fnr ?: subject
		val aud = audience_ ?: audience
		return mockOAuth2Server.issueToken(
			issuerId = "tokenx",
			clientId = "application",
			tokenCallback = DefaultOAuth2TokenCallback(
				issuerId = "tokenx",
				subject = pid,
				typeHeader = JOSEObjectType.JWT.type,
				claims = mapOf("aud" to listOf(aud), "pid" to pid),
				expiry = expiry
			)
		).serialize()
	}

}

