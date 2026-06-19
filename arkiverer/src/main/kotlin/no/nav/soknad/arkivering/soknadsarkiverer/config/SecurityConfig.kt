package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain


@Configuration
@Profile("!(test | docker)")
class SecurityConfig(
	@Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") private val azureadIssuer: String,
	@Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") private val azureadJwkUri: String,
	@Value("\${spring.security.oauth2.resourceserver.jwt.audiences}") private val azureadAudience: String
){

	val audienceValidator = JwtAudienceValidator(azureadAudience)
	val issuerValidator = JwtIssuerValidator(azureadIssuer)

	@Bean
	fun azureJwtDecoder(): JwtDecoder {
		val nimbusJwtDecoder = NimbusJwtDecoder.withJwkSetUri(azureadJwkUri).build()

		val withValidators = DelegatingOAuth2TokenValidator(audienceValidator, issuerValidator)

		nimbusJwtDecoder.setJwtValidator(withValidators)
		return nimbusJwtDecoder
	}

	@Bean
	fun securityFilterChain(
		http: HttpSecurity,
		azureJwtDecoder: JwtDecoder,
	): SecurityFilterChain {


		val delegatingDecoder = JwtDecoder { token ->
			val jwt = azureJwtDecoder.decode(token)
			if (audienceValidator.validate(jwt).hasErrors()) throw BadCredentialsException("Manglende eller ugyldig aud i token")
			if (issuerValidator.validate(jwt).hasErrors()) throw BadCredentialsException("Manglende eller ugyldig iss i token")
			return@JwtDecoder jwt
		}

		val jwtAuthConverter = JwtAuthenticationConverter().apply {
			setJwtGrantedAuthoritiesConverter(JwtGrantedAuthoritiesConverter())
		}

		http
			// Usikker på om det er riktig å ha csrf aktivisert. Det logges Using @Deprecated Class org.eclipse.jetty.ee11.servlets.CrossOriginFilter
			//.csrf { csrf -> csrf.disable() }
			.csrf { csrf ->
				csrf.ignoringRequestMatchers("/internal/health/**")
			}
			.authorizeHttpRequests { auth ->
				// Authorize all HTTP requests
				auth.requestMatchers( "/internal/isAlive","/internal/isReady","/internal/health/**", "/health/**").permitAll()
				auth.anyRequest().authenticated()
			}
			.oauth2ResourceServer { rs ->
				rs.jwt { jwt ->
					jwt.decoder(delegatingDecoder) // Lagt til for å kunne teste at validering av aud og iss fungerer
					jwt.jwtAuthenticationConverter(jwtAuthConverter)
				}
			}
		return http.build()
	}

}
