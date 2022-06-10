package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

//@ConstructorBinding
@Configuration
@ConfigurationProperties(prefix = "kafka")
data class KafkaConfig(
	var applicationId : String,
	var brokers: String,
	var bootstrappingTimeout: Int,
	var delayBeforeKafkaInitialization: Long,
  var security : SecurityConfig,
	var topics : Topics,
	var schemaRegistry: SchemaRegistry
)

data class SecurityConfig(
	var enabled  : String,
	var protocol : String,
	var keyStoreType : String,
	var keyStorePath : String,
	var keyStorePassword : String,
	var trustStorePath : String,
	var trustStorePassword : String
)

data class Topics(
	var mainTopic : String,
	var processingTopic : String,
	var messageTopic : String,
	var metricsTopic : String
)

data class SchemaRegistry(
	var url : String,
	var username : String,
	var password : String
)
