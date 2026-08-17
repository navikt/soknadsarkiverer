package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.apache.hc.client5.http.classic.HttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory


@Configuration
class RestConfig {
	@Bean
	fun requestFactory(httpClient: HttpClient): ClientHttpRequestFactory {
		return HttpComponentsClientHttpRequestFactory(httpClient)
	}

	@Bean
	fun httpClient(): HttpClient {
		return HttpClients.createDefault()
	}
}
