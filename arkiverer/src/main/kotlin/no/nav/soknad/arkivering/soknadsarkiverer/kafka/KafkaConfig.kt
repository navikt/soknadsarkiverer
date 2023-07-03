package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kafka")
data class KafkaConfig (
	 val applicationId: String,
	 val brokers: String,
	 val bootstrappingTimeout: String,
	 val delayBeforeKafkaInitialization: String,
	 val security: SecurityConfig,
	 val topics: Topics,
	 val schemaRegistry: SchemaRegistry,
)

data class SecurityConfig(
	val enabled  : String,
	val protocol : String,
	val keyStoreType : String,
	val keyStorePath : String,
	val keyStorePassword : String,
	val trustStorePath : String,
	val trustStorePassword : String
)

data class Topics(
	val mainTopic : String,
	val processingTopic : String,
	val messageTopic : String,
	val metricsTopic : String
)

data class SchemaRegistry(
	val url : String,
	val username : String,
	val password : String
)
