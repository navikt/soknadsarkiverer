
package no.nav.soknad.arkivering.soknadsarkiverer.config
import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import java.io.File

private val defaultProperties = ConfigurationMap(mapOf(
	"APP_VERSION" to "",
	"SRVSSOKNADSARKIVERER_USERNAME" to "srvsoknadsarkiverer",
	"SRVSSOKNADSARKIVERER_PASSWORD" to "",
	"SCHEMA_REGISTRY_URL" to "http://localhost:8081",
	"KAFKA_BOOTSTRAP_SERVERS" to "localhost:29092",
	"KAFKA_CLIENTID" to "srvsoknadarkiverer",
	"KAFKA_SECURITY" to "",
	"KAFKA_SECPROT" to "",
	"KAFKA_SASLMEC" to "",
	"KAFKA_TOPIC" to "privat-soknadInnsendt-sendsoknad-v1-default",
	"APPLICATION_PROFILE" to ""
))

val appConfig =
	EnvironmentVariables() overriding
		systemProperties() overriding
		ConfigurationProperties.fromResource(Configuration::class.java, "/application.yml") overriding
		ConfigurationProperties.fromResource(Configuration::class.java, "/local.properties") overriding
		defaultProperties

private fun String.configProperty(): String = appConfig[Key(this, stringType)]

fun readFileAsText(fileName: String) = try { File(fileName).readText(Charsets.UTF_8) } catch (e :Exception ) { "" }

data class AppConfiguration(val kafkaConfig: KafkaConfig = KafkaConfig()) {
	data class KafkaConfig(
		val profiles: String = "APPLICATION_PROFILE".configProperty(),
		val version: String = "APP_VERSION".configProperty(),
		val username: String = "SRVSSOKNADSMOTTAKER_USERNAME".configProperty(),
		val password: String = (when {
			"" == profiles || "test".equals(profiles, true) -> "SRVSSOKNADSMOTTAKER_PASSWORD".configProperty()
			else -> readFileAsText("/var/run/secrets/nais.io/serviceuser/password")
		}),
		val schemaRegistryUrl: String = "SCHEMA_REGISTRY_URL".configProperty(),
		val servers: String = "KAFKA_BOOTSTRAP_SERVERS".configProperty(),
		val clientId: String = "KAFKA_CLIENTID".configProperty(),
		val secure: String = "KAFKA_SECURITY".configProperty(),
		val protocol: String = "KAFKA_SECPROT".configProperty(), // SASL_PLAINTEXT | SASL_SSL
		val salsmec: String = "KAFKA_SASLMEC".configProperty(), // PLAIN
		val topic: String = "KAFKA_TOPIC".configProperty(),
		val saslJaasConfig: String = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";"
	)
}
