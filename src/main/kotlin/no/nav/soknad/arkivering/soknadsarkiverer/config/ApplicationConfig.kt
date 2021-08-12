package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import java.io.File
import javax.annotation.Priority

const val kafkaInputTopic = "privat-soknadInnsendt-v1-teamsoknad"
const val kafkaProcessingTopic = "privat-soknadInnsendt-processingEventLog-v1-teamsoknad"
const val kafkaMessageTopic = "privat-soknadInnsendt-messages-v1-teamsoknad"
const val kafkaMetricsTopic = "privat-soknadInnsendt-metrics-v1-teamsoknad"

private val defaultProperties = ConfigurationMap(mapOf(
	"SOKNADSARKIVERER_USERNAME" to "arkiverer",
	"SOKNADSARKIVERER_PASSWORD" to "",
	"SCHEMA_REGISTRY_URL" to "http://localhost:8081",
	"KAFKA_BOOTSTRAP_SERVERS" to "localhost:29092",
	"KAFKA_SECURITY" to "",
	"KAFKA_SECPROT" to "",
	"KAFKA_SASLMEC" to "",
	"KAFKA_GROUPID" to "soknadsarkiverer-group-defaultid",
	"BOOTSTRAPPING_TIMEOUT" to 120.toString(),
	"KAFKA_INPUT_TOPIC" to kafkaInputTopic,
	"KAFKA_PROCESSING_TOPIC" to kafkaProcessingTopic,
	"KAFKA_MESSAGE_TOPIC" to kafkaMessageTopic,
	"KAFKA_METRICS_TOPIC" to kafkaMetricsTopic,

	"APPLICATION_PROFILE" to "spring",
	"MAX_MESSAGE_SIZE" to (1024 * 1024 * 300).toString(),
	"CLIENTSECRET" to "",

	"JOARK_HOST" to "http://localhost:8092",
	"JOARK_URL" to "/rest/journalpostapi/v1/journalpost",
	"FILESTORAGE_HOST" to "http://localhost:9042",
	"FILESTORAGE_URL" to "/filer?ids=",
	"SHARED_PASSWORD" to "password",

	"ADMIN_USER" to "admin",
	"ADMIN_USER_PASSWORD" to "password",
))

private val secondsBetweenRetries = listOf(1, 25, 60, 120, 600, 1200) // As many retries will be attempted as there are elements in the list.
private val secondsBetweenRetriesForTests = listOf(0, 1, 1, 1, 1, 1)  // Note! Also update end-to-end-tests if the list size is changed!
private const val startUpSeconds: Long = 90 //  1,5 minutes before starting processing incoming
const val startUpSecondsForTest: Long = 8 // 8 seconds before starting processing incoming


private val appConfig =
	EnvironmentVariables() overriding
		systemProperties() overriding
		ConfigurationProperties.fromResource(Configuration::class.java, "/application.yml") overriding
		ConfigurationProperties.fromResource(Configuration::class.java, "/local.properties") overriding
		defaultProperties

private fun String.configProperty(): String = appConfig[Key(this, stringType)]

fun readFileAsText(fileName: String, default: String = "") = try { File(fileName).readText(Charsets.UTF_8) } catch (e: Exception) { default }

data class AppConfiguration(val kafkaConfig: KafkaConfig = KafkaConfig(), val config: Config = Config(), val state: State = State()) {
	data class KafkaConfig(
		val username: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/username", "SOKNADSARKIVERER_USERNAME".configProperty()),
		val password: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/password", "SOKNADSARKIVERER_PASSWORD".configProperty()),
		val servers: String = readFileAsText("/var/run/secrets/nais.io/kv/kafkaBootstrapServers", "KAFKA_BOOTSTRAP_SERVERS".configProperty()),
		val schemaRegistryUrl: String = "SCHEMA_REGISTRY_URL".configProperty(),
		val secure: String = "KAFKA_SECURITY".configProperty(),
		val protocol: String = "KAFKA_SECPROT".configProperty(), // SASL_PLAINTEXT | SASL_SSL
		val salsmec: String = "KAFKA_SASLMEC".configProperty(), // PLAIN
		val saslJaasConfig: String = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";",

		val inputTopic: String = "KAFKA_INPUT_TOPIC".configProperty(),
		val processingTopic: String = "KAFKA_PROCESSING_TOPIC".configProperty(),
		val messageTopic: String = "KAFKA_MESSAGE_TOPIC".configProperty(),
		val metricsTopic: String = "KAFKA_METRICS_TOPIC".configProperty(),
		val bootstrappingTimeout: String = "BOOTSTRAPPING_TIMEOUT".configProperty(),
		val groupId: String = "KAFKA_GROUPID".configProperty()
	)

	data class Config(
		val joarkHost: String = readFileAsText("/var/run/secrets/nais.io/kv/JOARK_HOST", "JOARK_HOST".configProperty()),
		val joarkUrl: String = "JOARK_URL".configProperty(),
		val username: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/username", "SOKNADSARKIVERER_USERNAME".configProperty()),
		val sharedPassword: String = readFileAsText("/var/run/secrets/nais.io/kv/SHARED_PASSWORD", "SHARED_PASSWORD".configProperty()),
		val clientsecret: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/password", "CLIENTSECRET".configProperty()),
		val filestorageHost: String = "FILESTORAGE_HOST".configProperty(),
		val filestorageUrl: String = "FILESTORAGE_URL".configProperty(),
		val retryTime: List<Int> = if (!"test".equals("APPLICATION_PROFILE".configProperty(), true)) secondsBetweenRetries else secondsBetweenRetriesForTests,
		val secondsAfterStartupBeforeStarting: Long = if (!"test".equals("APPLICATION_PROFILE".configProperty(), true)) startUpSeconds else startUpSecondsForTest,
		val profile: String = "APPLICATION_PROFILE".configProperty(),
		val maxMessageSize: Int = "MAX_MESSAGE_SIZE".configProperty().toInt(),
		val adminUser: String = readFileAsText("/var/run/secrets/nais.io/kv/ADMIN_USER", "ADMIN_USER".configProperty()),
		val adminUserPassword: String = readFileAsText("/var/run/secrets/nais.io/kv/ADMIN_USER_PASSWORD", "ADMIN_USER_PASSWORD".configProperty()),
	)

	data class State(
		var started: Boolean = false,
		var up: Boolean = true,
		var ready: Boolean = false,
		var stopping: Boolean = false,
		var busyCounter: Int = 0
	)
}

@org.springframework.context.annotation.Configuration
@Priority(-1)
class ConfigConfig(private val env: ConfigurableEnvironment) {

	@Bean
	fun appConfiguration(): AppConfiguration {
		val appConfiguration = AppConfiguration()
		env.setActiveProfiles(appConfiguration.config.profile)
		println("Using profile '${appConfiguration.config.profile}'")
		appConfiguration.state.ready = true
		appConfiguration.state.up = true

		return appConfiguration
	}
}
