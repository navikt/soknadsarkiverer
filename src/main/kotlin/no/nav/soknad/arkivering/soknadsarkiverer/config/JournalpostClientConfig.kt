package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate

@Configuration
class JournalpostClientConfig(private val appConfiguration: AppConfiguration,
															@Qualifier("archiveRestTemplate") private val restTemplate: RestTemplate
) {

	@Bean
	fun journalpostClient() = JournalpostClient(appConfiguration, restTemplate)


}

