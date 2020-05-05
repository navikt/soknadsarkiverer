package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("application")
class ApplicationProperties {

	lateinit var kafkaBootstrapServers: String
	lateinit var schemaRegistryUrl: String
	lateinit var inputTopic: String
	lateinit var processingTopic: String
	lateinit var joarkHost: String
	lateinit var joarkUrl: String
	lateinit var filestorageHost: String
	lateinit var filestorageUrl: String
	lateinit var kafkaRetrySleepTime: List<Int>
}
