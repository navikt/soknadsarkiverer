package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.security.token.support.client.spring.ClientConfigurationProperties
import no.nav.soknad.arkivering.soknadsarkiverer.service.tokensupport.TokenService
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.concurrent.TimeUnit

@Configuration
class FilestorageClientConfiguration {

	@Bean
	@Profile("prod | dev")
	@Qualifier("filestorageClient")
	fun filestorageClient(
		clientConfigProperties: ClientConfigurationProperties,
		oAuth2AccessTokenService: OAuth2AccessTokenService,
		fileFetchTimeoutProperties: FileFetchTimeoutProperties
	): OkHttpClient {

		val clientProperties = clientConfigProperties.registration["soknadsfillager"]
		val tokenService = TokenService(clientProperties!!, oAuth2AccessTokenService)

		return OkHttpClient().newBuilder()
			.connectTimeout(fileFetchTimeoutProperties.connectTimeout.toLong(), TimeUnit.SECONDS)
			.callTimeout(fileFetchTimeoutProperties.callTimeout.toLong(), TimeUnit.SECONDS)
			.readTimeout(fileFetchTimeoutProperties.readTimeout.toLong(), TimeUnit.MINUTES)
			.writeTimeout(fileFetchTimeoutProperties.writeTimeout.toLong(), TimeUnit.MINUTES)
			.addInterceptor {
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
