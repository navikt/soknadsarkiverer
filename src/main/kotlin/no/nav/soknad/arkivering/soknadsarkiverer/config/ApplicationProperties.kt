package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("application")
class ApplicationProperties {
	lateinit var kafkaBootstrapServers: String
	lateinit var kafkaTopic: String
	lateinit var joarkHost: String
	lateinit var joarkUrl: String
}
