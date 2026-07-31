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
	val processingTopic : String,
	val messageTopic : String,
	val arkiveringstilbakemeldingTopic : String,
	val metricsTopic : String,
	val nologinSubmissionTopic : String,
	val loggedinSubmissionTopic : String,
	// v3 JSON processing-event topic (issue #264). Read-only during phase one: bootstrapping/replay
	// also consumes this topic, but production writers remain v2 Avro (`processingTopic`) until the
	// issue #265 cutover. Defaulted so existing deployments and tests keep working without a v3 topic.
	val processingTopicV3 : String = "privat-soknadinnsending-processingeventlog-v3",
)

data class SchemaRegistry(
	val url : String,
	val username : String,
	val password : String
)
