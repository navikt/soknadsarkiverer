package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain


@Configuration
@Profile("test", "docker")
class SecurityConfigLocal(
	@Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") private val azureadIssuer: String,
	@Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") private val azureadJwkUri: String,
	@Value("\${spring.security.oauth2.resourceserver.jwt.audiences}") private val azureadAudience: String
) {

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
	): SecurityFilterChain {

		http
			.csrf { csrf ->
				csrf.disable()
			}
			.authorizeHttpRequests { auth ->
				auth.anyRequest().permitAll()
			}
		return http.build()
	}

}

