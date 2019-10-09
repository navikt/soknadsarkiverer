package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("application")
class ApplicationProperties {
	lateinit var joarkHost: String
	lateinit var joarkUrl: String
	var kafka = Kafka()

	class Kafka {
		lateinit var bootstrapServers: String
	}
}
