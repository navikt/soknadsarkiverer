package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

//private val defaultProperties = ConfigurationMap(mapOf(

	//@TODO for local start //"JOARK_HOST" to "http://localhost:8092",

	//@TODO for local start "FILESTORAGE_HOST" to "http://localhost:9042",
	//"FILESTORAGE_URL" to "/files/",

	//@TODO for local start "ADMIN_USER" to "admin",
	//@TODO for local start "ADMIN_USER_PASSWORD" to "password",
//))

data class ApplicationState(
		var alive: Boolean = false,
		var ready: Boolean = false,
		var stopping: Boolean = false,
		var busyCounter: Int = 0
)

@Configuration
class AppStateConfig() {
	@Bean
	fun appState() = ApplicationState()

}

