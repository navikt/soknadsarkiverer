package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken


@Component("issuerChecker")
class IssuerChecker {

	private val log = LoggerFactory.getLogger(javaClass)

	fun hasIssuer(authentication: Authentication, allowedIssuers: Collection<String>): Boolean {
		if (authentication !is JwtAuthenticationToken) {
			log.warn("Ingen JwtAuthenticationToken funnet, authentication er ${authentication::class.java.simpleName}")
			return false
		}

		val issuer = authentication.token.issuer?.toString() ?: return false
		log.debug("Issuer for token: $issuer")

		return allowedIssuers.any { issuer.contains(it, ignoreCase = true) }
	}
}
