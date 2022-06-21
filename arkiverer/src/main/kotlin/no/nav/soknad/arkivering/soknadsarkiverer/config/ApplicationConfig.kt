package no.nav.soknad.arkivering.soknadsarkiverer.config

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import java.io.File
import javax.annotation.Priority

private val defaultProperties = ConfigurationMap(mapOf(

	"MAX_MESSAGE_SIZE" to (1024 * 1024 * 300).toString(),
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


data class AppConfiguration(val config: Config = Config(), val state: State = State()) {


	data class Config(
	//	val joarkHost: String = "JOARK_HOST".configProperty(),
	//	val joarkUrl: String = "JOARK_URL".configProperty(),
		val innsendingUsername: String = "INNSENDING_USERNAME".configProperty(),
		val innsendingPassword: String = "INNSENDING_PASSWORD".configProperty(),
		val filestorageHost: String = "FILESTORAGE_HOST".configProperty(),
		val filestorageUrl: String = "FILESTORAGE_URL".configProperty(),
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
class ConfigConfig() {

	@Bean
	fun appConfiguration() = AppConfiguration()
}
