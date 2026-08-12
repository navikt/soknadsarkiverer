package no.nav.soknad.arkivering.soknadsarkiverer.config.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@Profile("test", "docker")
class LocalSecurityConfig(
	private val securityFilterChainFactory: SecurityFilterChainFactory,
) {
	private val disableAuth = false
	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
		if (disableAuth) {
			securityFilterChainFactory.localAuthDisabled(http)
		} else {
			securityFilterChainFactory.authenticated(http)
		}
}
