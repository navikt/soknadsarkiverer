package no.nav.soknad.arkivering.soknadsarkiverer.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant


@Component
class LocalDevelopmentAuthenticationFactory(
	@Value("\${auth.issuers.azuread.issuer-uri}") private val azureadIssuer: String,
) {
	fun create(): JwtAuthenticationToken {
		val now = Instant.now()
		val jwt = Jwt.withTokenValue("local-auth-disabled")
			.header("alg", "none")
			.subject("local-dev-user")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(3600))
			.claim("iss", azureadIssuer)
			.claim("aud", "local-dev")
			.claim("NAVident", "A123456")
			.claim("preferred_username", "local-dev@example.com")
			.claim("scp", "defaultaccess serviceklage-klassifisering")
			.build()

		return JwtAuthenticationToken(jwt)
	}
}

@Component
class LocalDevelopmentAuthenticationFilter(
	private val localDevelopmentAuthenticationFactory: LocalDevelopmentAuthenticationFactory,
) : OncePerRequestFilter() {
	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val securityContext = SecurityContextHolder.getContext()
		if (securityContext.authentication == null) {
			securityContext.authentication = localDevelopmentAuthenticationFactory.create()
		}

		filterChain.doFilter(request, response)
	}
}
