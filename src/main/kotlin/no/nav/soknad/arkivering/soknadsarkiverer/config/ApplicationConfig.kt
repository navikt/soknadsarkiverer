
package no.nav.soknad.arkivering.soknadsarkiverer.config
import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties

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
