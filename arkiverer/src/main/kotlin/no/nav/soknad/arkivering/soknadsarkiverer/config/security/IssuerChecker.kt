package no.nav.soknad.arkivering.soknadsarkiverer.config.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component("issuerChecker")
class IssuerChecker(
	@Value("\${auth.issuers.azuread.issuer-uri}") private val azureadIssuer: String
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun hasIssuer(authentication: Authentication): Boolean {
		if (authentication !is JwtAuthenticationToken) {
			log.warn("Ingen JwtAuthenticationToken funnet, authentication er ${authentication::class.java.simpleName}")
			return false
		}

		val jwt = authentication.token
		val issuer = jwt.issuer?.toString() ?: return false
		if (!issuer.equals(azureadIssuer, ignoreCase = true)) {
			log.info("Avvist: issuer $issuer er ikke konfigurert AzureAD issuer")
			return false
		}

		log.debug("Issuer for token: $issuer")

		return true
	}
}
