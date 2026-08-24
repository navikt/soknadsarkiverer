package no.nav.soknad.arkivering.soknadsarkiverer.config.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.stereotype.Component


@Component
class SecurityFilterChainFactory(
	@Value("\${auth.issuers.azuread.issuer-uri}") private val azureadIssuer: String,
	@Qualifier("azureJwtDecoder") private val azureJwtDecoder: JwtDecoder,
	private val localDevelopmentAuthenticationFilter: LocalDevelopmentAuthenticationFilter,
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun authenticated(http: HttpSecurity): SecurityFilterChain {

		http
			.csrf { csrf ->
				csrf.ignoringRequestMatchers(*PUBLIC_REQUEST_MATCHERS)
			}
			.authorizeHttpRequests { auth ->
				auth.requestMatchers(*PUBLIC_REQUEST_MATCHERS).permitAll()
				auth.anyRequest().authenticated()
			}
			.oauth2ResourceServer { rs ->
				rs.jwt { jwt ->
					jwt.decoder(delegatingJwtDecoder())
					jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
				}
			}

		return http.build()
	}

	fun localAuthDisabled(http: HttpSecurity): SecurityFilterChain {
		http
			.csrf { csrf ->
				csrf.disable()
			}
			.authorizeHttpRequests { auth ->
				auth.requestMatchers(*PUBLIC_REQUEST_MATCHERS).permitAll()
				auth.anyRequest().permitAll()
			}
			.addFilterBefore(localDevelopmentAuthenticationFilter, AnonymousAuthenticationFilter::class.java)

		return http.build()
	}

	private fun delegatingJwtDecoder(): JwtDecoder =

		JwtDecoder { token ->
			JwtIssuerValidator(azureadIssuer).validate(azureJwtDecoder.decode(token)).let { result ->
				if (result.hasErrors()) {
					logger.info("Invalid issuer in token: ${result.errors}")
					throw BadCredentialsException("Invalid issuer in token: ${result.errors}")
				}
			}
			azureJwtDecoder.decode(token)
		}

	private fun jwtAuthenticationConverter() =
		JwtAuthenticationConverter().apply {
			setJwtGrantedAuthoritiesConverter(JwtGrantedAuthoritiesConverter())
		}


	private companion object {
		private val PUBLIC_REQUEST_MATCHERS = arrayOf(
			"/internal/isAlive",
			"/internal/isReady",
			"/internal/health/**",
			"/internal/metrics",
			"/internal/prometheus",
			"/public/**",
			"/swagger-ui",
			"/swagger-ui.html",
			"/swagger-ui/**",
			"/v3/api-docs",
			"/v3/api-docs.yaml",
			"/v3/api-docs/**",
		)
	}
}

