package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.oauth2.EnableOAuth2Client
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport.TokenService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@EnableOAuth2Client(cacheEnabled = true)
@EnableJwtTokenValidation(ignore = [
	"org.springframework",
	"no.nav.soknad.arkivering.soknadsarkiverer.supervision.HealthCheck",
	"io.swagger",
	"org.springdoc",
	"org.webjars.swagger-ui"
])
@Profile("dev | prod")
@Configuration
class JwtTokenValidationConfig {

	@Bean
	fun tokenService(oAuth2AccessTokenService: OAuth2AccessTokenService,clientProperties: ClientProperties) :TokenService = TokenService(clientProperties,oAuth2AccessTokenService)
}
