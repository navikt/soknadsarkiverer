package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Instant
import kotlin.collections.get


@Service
class TokenExchangeService(
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun performJwtBearerExchange(context: OAuth2AuthorizationContext): OAuth2AuthorizedClient? {
		log.info(
			"Exchange token using ${context.clientRegistration.authorizationGrantType.value} for ${context.clientRegistration.registrationId}  and scope ${
				context.clientRegistration.scopes.joinToString(
					" "
				)
			} "
		)
		val principal: Authentication = context.principal
		if (principal !is JwtAuthenticationToken) {
			log.warn("invalid_principal", "Expected JwtAuthenticationToken but was ${principal.javaClass}")
			throw OAuth2AuthorizationException(
				OAuth2Error("invalid_principal", "Expected JwtAuthenticationToken but was ${principal.javaClass}", null)
			)
		}

		val registration = context.clientRegistration
		val tokenValue =
			context.attributes["subject_token"] as? String ?: principal.token.tokenValue ?: return null

		val body = LinkedMultiValueMap<String, String>().apply {
			add(OAuth2ParameterNames.GRANT_TYPE, "urn:ietf:params:oauth:grant-type:jwt-bearer")
			add("client_id", registration.clientId)
			add("client_secret", registration.clientSecret ?: "")
			add("assertion", tokenValue)
			add("scope", registration.scopes.joinToString(" "))
			add("requested_token_use", "on_behalf_of")
		}

		// Bruker den injiserte builderen til å lage en RestClient for dette spesifikke kallet
		val restClient = RestClient.builder()
			.baseUrl(registration.providerDetails.tokenUri)
			.build()

		log.info("Ready to exchange token for ${registration.registrationId} og scope ${registration.scopes.joinToString()}")
		val response = restClient.post()
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(body)
			.retrieve()
			.body(Map::class.java) ?: throw OAuth2AuthorizationException(
			OAuth2Error("invalid_token_response", "Empty token response", null)
		)

		val accessTokenValue = response["access_token"] as? String
			?: throw OAuth2AuthorizationException(OAuth2Error("invalid_token", "No access_token in response", null))

		log.info("Received access token for ${registration.registrationId} og scope ${registration.scopes.joinToString()}")

		val expiresIn = (response["expires_in"] as? Number)?.toLong() ?: 3600L
		val issuedAt = Instant.now()
		val expiresAt = issuedAt.plusSeconds(expiresIn)

		val accessToken = OAuth2AccessToken(
			OAuth2AccessToken.TokenType.BEARER,
			accessTokenValue,
			issuedAt,
			expiresAt,
			registration.scopes
		)

		log.info("Exchange token successful for ${registration.registrationId} og scope ${registration.scopes.joinToString()}")
		return OAuth2AuthorizedClient(registration, principal.name, accessToken)
	}
}
