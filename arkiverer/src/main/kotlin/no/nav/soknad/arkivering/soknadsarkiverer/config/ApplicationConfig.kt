package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import java.io.File
import javax.annotation.Priority

const val kafkaInputTopic = "privat-soknadinnsending-v1-dev"
const val kafkaProcessingTopic = "privat-soknadinnsending-processingeventlog-v1-dev"
const val kafkaMessageTopic = "privat-soknadinnsending-messages-v1-dev"
const val kafkaMetricsTopic = "privat-soknadinnsending-metrics-v1-dev"

private val defaultProperties = ConfigurationMap(mapOf(
	"KAFKA_SCHEMA_REGISTRY" to "http://localhost:8081",
	"KAFKA_BROKERS" to "localhost:29092",
	"KAFKA_SECURITY" to "FALSE",
	"KAFKA_GROUPID" to "soknadsarkiverer-group-defaultid",
	"KAFKA_SCHEMA_REGISTRY_USER" to "username",
	"KAFKA_SCHEMA_REGISTRY_PASSWORD" to "password",

	"KAFKA_KEYSTORE_PATH" to "",
	"KAFKA_CREDSTORE_PASSWORD" to "",
	"KAFKA_TRUSTSTORE_PATH" to "",

	"BOOTSTRAPPING_TIMEOUT" to 120.toString(),
	"DELAY_BEFORE_KAFKA_INITIALIZATION" to 5.toString(),
	"KAFKA_INPUT_TOPIC" to kafkaInputTopic,
	"KAFKA_PROCESSING_TOPIC" to kafkaProcessingTopic,
	"KAFKA_MESSAGE_TOPIC" to kafkaMessageTopic,
	"KAFKA_METRICS_TOPIC" to kafkaMetricsTopic,

	"SPRING_PROFILES_ACTIVE" to "spring",
	"MAX_MESSAGE_SIZE" to (1024 * 1024 * 300).toString(),
	"CLIENTSECRET" to "",

	"INNSENDING_USERNAME" to "sender",
	"INNSENDING_PASSWORD" to "password",
	"JOARK_HOST" to "http://localhost:8092",
	"JOARK_URL" to "/rest/journalpostapi/v1/journalpost",
	"FILESTORAGE_HOST" to "http://localhost:9042",
	"FILESTORAGE_URL" to "/files/",

	"ADMIN_USER" to "admin",
	"ADMIN_USER_PASSWORD" to "password",
))

private val secondsBetweenRetries = listOf(1, 60, 120, 600, 1200, 3600) // As many retries will be attempted as there are elements in the list.
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
		val kafkaBrokers: String = "KAFKA_BROKERS".configProperty(),
		val schemaRegistryUrl: String = "KAFKA_SCHEMA_REGISTRY".configProperty(),
		val schemaRegistryUsername: String = "KAFKA_SCHEMA_REGISTRY_USER".configProperty(),
		val schemaRegistryPassword: String = "KAFKA_SCHEMA_REGISTRY_PASSWORD".configProperty(),
		val secure: String = "KAFKA_SECURITY".configProperty(),
		val protocol: String = "SSL", // SASL_PLAINTEXT | SASL_SSL
		val keyStorePath: String = "KAFKA_KEYSTORE_PATH".configProperty(),
		val keyStorePassword: String = "KAFKA_CREDSTORE_PASSWORD".configProperty(),
		val trustStorePath: String = "KAFKA_TRUSTSTORE_PATH".configProperty(),
		val trustStorePassword: String = "KAFKA_CREDSTORE_PASSWORD".configProperty(),
		val keystoreType: String = "PKCS12",

		val inputTopic: String = "KAFKA_INPUT_TOPIC".configProperty(),
		val processingTopic: String = "KAFKA_PROCESSING_TOPIC".configProperty(),
		val messageTopic: String = "KAFKA_MESSAGE_TOPIC".configProperty(),
		val metricsTopic: String = "KAFKA_METRICS_TOPIC".configProperty(),
		val bootstrappingTimeout: String = "BOOTSTRAPPING_TIMEOUT".configProperty(),
		val delayBeforeKafkaInitialization: String = "DELAY_BEFORE_KAFKA_INITIALIZATION".configProperty(),
		val groupId: String = "KAFKA_GROUPID".configProperty()
	)

	data class Config(
		val joarkHost: String = "JOARK_HOST".configProperty(),
		val joarkUrl: String = "JOARK_URL".configProperty(),
		val innsendingUsername: String = "INNSENDING_USERNAME".configProperty(),
		val innsendingPassword: String = "INNSENDING_PASSWORD".configProperty(),
		val filestorageHost: String = "FILESTORAGE_HOST".configProperty(),
		val filestorageUrl: String = "FILESTORAGE_URL".configProperty(),
		val retryTime: List<Int> = if (!"test".equals("SPRING_PROFILES_ACTIVE".configProperty(), true)) secondsBetweenRetries else secondsBetweenRetriesForTests,
		val secondsAfterStartupBeforeStarting: Long = if (!"test".equals("SPRING_PROFILES_ACTIVE".configProperty(), true)) startUpSeconds else startUpSecondsForTest,
		val profile: String = "SPRING_PROFILES_ACTIVE".configProperty(),
		val maxMessageSize: Int = "MAX_MESSAGE_SIZE".configProperty().toInt(),
		val adminUser: String = "ADMIN_USER".configProperty(),
		val adminUserPassword: String = "ADMIN_USER_PASSWORD".configProperty(),
	)

	data class State(
		var alive: Boolean = false,
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
		return appConfiguration
	}
}
