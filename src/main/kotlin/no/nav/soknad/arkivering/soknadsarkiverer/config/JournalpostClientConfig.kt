package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi.JoarkArchiverMockClient
import no.nav.soknad.arkivering.soknadsarkiverer.consumer.rest.journalpostapi.JournalpostClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestTemplate

@Configuration
class JournalpostClientConfig(private val appConfiguration: AppConfiguration,
															@Qualifier("archiveRestTemplate") private val restTemplate: RestTemplate//,
//															@Qualifier("basicRestTemplate") private val restTestTemplate: RestTemplate
) {

	@Bean
//	@Profile("prod | test")
	fun journalpostClient() = JournalpostClient(appConfiguration, restTemplate)

/*
	@Bean
	@Profile("!prod && !test")
	fun journalpostTestClient() = JoarkArchiverMockClient(restTestTemplate, appConfiguration)
*/

}

