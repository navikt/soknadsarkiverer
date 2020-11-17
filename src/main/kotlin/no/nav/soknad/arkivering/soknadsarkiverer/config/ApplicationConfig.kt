package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import java.io.File
import javax.annotation.Priority

private val defaultProperties = ConfigurationMap(mapOf(
	"APP_VERSION" to "",
	"SOKNADSARKIVERER_USERNAME" to "arkiverer",
	"SOKNADSARKIVERER_PASSWORD" to "",
	"SCHEMA_REGISTRY_URL" to "http://localhost:8081",
	"KAFKA_BOOTSTRAP_SERVERS" to "localhost:29092",
	"KAFKA_CLIENTID" to "arkiverer",
	"KAFKA_SECURITY" to "",
	"KAFKA_SECPROT" to "",
	"KAFKA_SASLMEC" to "",
	"SPRING_PROFILES_ACTIVE" to "spring",
	"KAFKA_INPUT_TOPIC" to "privat-soknadInnsendt-v1-default",
	"KAFKA_PROCESSING_TOPIC" to "privat-soknadInnsendt-processingEventLog-v1-default",
	"KAFKA_MESSAGE_TOPIC" to "privat-soknadInnsendt-messages-v1-default",
	"MAX_MESSAGE_SIZE" to (1024 * 1024 * 300).toString(),

	"JOARK_HOST" to "http://localhost:8092",
	"JOARK_URL" to "/rest/journalpostapi/v1/journalpost",
	"FILESTORAGE_HOST" to "http://localhost:9042",
	"FILESTORAGE_URL" to "/filer?ids=",
	"SHARED_PASSWORD" to "password",

	"ADMIN_USER" to "admin",
	"ADMIN_USER_PASSWORD" to "password",

	"CLIENTID" to "",
	"CLIENTSECRET" to ""
))

private val secondsBetweenRetries = listOf(1, 25, 60, 120, 600)   // As many retries will be attempted as there are elements in the list.
private val secondsBetweenRetriesForTests = listOf(1, 1, 1, 1, 1) // As many retries will be attempted as there are elements in the list.


val appConfig =
	EnvironmentVariables() overriding
		systemProperties() overriding
		ConfigurationProperties.fromResource(Configuration::class.java, "/application.yml") overriding
		ConfigurationProperties.fromResource(Configuration::class.java, "/local.properties") overriding
		defaultProperties

private fun String.configProperty(): String = appConfig[Key(this, stringType)]

fun readFileAsText(fileName: String, default: String = "") = try { File(fileName).readText(Charsets.UTF_8) } catch (e: Exception) { default }

data class AppConfiguration(val kafkaConfig: KafkaConfig = KafkaConfig(), val config: Config = Config(), val state: State = State() ) {
	data class KafkaConfig(
		val version: String = "APP_VERSION".configProperty(),
		val username: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/username", "SOKNADSARKIVERER_USERNAME".configProperty()),
		var password: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/password", "SOKNADSARKIVERER_PASSWORD".configProperty()),
		val servers: String = readFileAsText("/var/run/secrets/nais.io/kv/kafkaBootstrapServers", "KAFKA_BOOTSTRAP_SERVERS".configProperty()),
		val schemaRegistryUrl: String = "SCHEMA_REGISTRY_URL".configProperty(),
		val clientId: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/username", "KAFKA_CLIENTID".configProperty()),
		val secure: String = "KAFKA_SECURITY".configProperty(),
		val protocol: String = "KAFKA_SECPROT".configProperty(), // SASL_PLAINTEXT | SASL_SSL
		val salsmec: String = "KAFKA_SASLMEC".configProperty(), // PLAIN
		val inputTopic: String = "KAFKA_INPUT_TOPIC".configProperty(),
		val processingTopic: String = "KAFKA_PROCESSING_TOPIC".configProperty(),
		val messageTopic: String = "KAFKA_MESSAGE_TOPIC".configProperty(),
		var saslJaasConfig: String = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";"
	)

	data class Config(
		val joarkHost: String = readFileAsText("/var/run/secrets/nais.io/kv/JOARK_HOST", "JOARK_HOST".configProperty()),
		val joarkUrl: String = "JOARK_URL".configProperty(),
		val username: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/username", "SOKNADSARKIVERER_USERNAME".configProperty()),
		var sharedPassword: String = readFileAsText("/var/run/secrets/nais.io/kv/SHARED_PASSWORD","SHARED_PASSWORD".configProperty()),
		var clientsecret: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/password", "CLIENTSECRET".configProperty()),
		val filestorageHost: String = "FILESTORAGE_HOST".configProperty(),
		val filestorageUrl: String = "FILESTORAGE_URL".configProperty(),
		val retryTime: List<Int> = if (!"test".equals("SPRING_PROFILES_ACTIVE".configProperty(), true)) secondsBetweenRetries else secondsBetweenRetriesForTests,
		val profile: String = "SPRING_PROFILES_ACTIVE".configProperty(),
		val maxMessageSize: Int = "MAX_MESSAGE_SIZE".configProperty().toInt(),
		val adminUser: String = readFileAsText("/var/run/secrets/nais.io/kv/ADMIN_USER","ADMIN_USER".configProperty()),
		var adminUserPassword: String = readFileAsText("/var/run/secrets/nais.io/kv/ADMIN_USER_PASSWORD","ADMIN_USER_PASSWORD".configProperty())
	)

	data class State(
		var up: Boolean = false,
		var ready: Boolean = false,
		var stopping: Boolean = false,
		var busyCounter: Int = 0
	)
}

@org.springframework.context.annotation.Configuration
@ConfigurationPropertiesScan
@Priority(-1)
class ConfigConfig(private val env: ConfigurableEnvironment) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Bean
	fun appConfiguration(): AppConfiguration {
		val appConfiguration = AppConfiguration()
		env.setActiveProfiles(appConfiguration.config.profile)
		appConfiguration.state.ready = true
		appConfiguration.state.up = true

		logger.info("MaxMessageSize=${appConfiguration.config.maxMessageSize}")
		return appConfiguration
	}
}
