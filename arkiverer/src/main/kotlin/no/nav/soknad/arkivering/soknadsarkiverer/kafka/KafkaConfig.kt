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
	// v3 JSON processing-event topic (issue #264/#265). This is now the only topic production code
	// writes processing events to (see KafkaPublisher); processingTopic (v2 Avro) is read-only,
	// kept around solely so bootstrapping/replay can still recover state written before the cutover.
	// Defaulted so existing deployments and tests keep working without an explicitly configured v3 topic.
	val processingTopicV3 : String = "privat-soknadinnsending-processingeventlog-v3",
	// v3 JSON metrics topic (issue #265). This is now the only topic production code writes metrics
	// to (see KafkaPublisher); metricsTopic (v2 Avro) is no longer written to.
	val metricsTopicV3 : String = "privat-soknadinnsending-metrics-v3",
)

data class SchemaRegistry(
	val url : String,
	val username : String,
	val password : String
)
