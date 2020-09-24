package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.*
import org.springframework.web.client.RestTemplate

@Profile("spring | test | docker")
@Configuration
class ArchiveRestTemplateTestConfig {

	@Bean
	@Profile("spring | test | docker")
	@Qualifier("archiveRestTemplate")
	@Scope("prototype")
	@Lazy
	fun archiveRestTestTemplate(@Qualifier("basicRestTemplate") restTemplate: RestTemplate) = restTemplate
}
