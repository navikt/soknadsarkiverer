package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport.TokenService
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class FilestorageClient {

	@Bean
	@Profile("prod | dev")
	@Qualifier("filestorageClient")
	fun filestorageClient(clientProperties: ClientProperties, oAuth2AccessTokenService: OAuth2AccessTokenService): OkHttpClient {
		val tokenService = TokenService(clientProperties, oAuth2AccessTokenService)

		return OkHttpClient().newBuilder().addInterceptor {
			val token = tokenService.getToken()

			val bearerRequest = it.request().newBuilder().headers(it.request().headers)
				.header("Authorization", "Bearer ${token.accessToken}").build()

			it.proceed(bearerRequest)
		}.build()
	}

	@Bean
	@Profile("!(prod | dev)")
	@Qualifier("filestorageClient")
	fun filestorageClientWithoutOAuth() = OkHttpClient.Builder().build()
}
