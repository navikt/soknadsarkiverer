package no.nav.soknad.arkivering.soknadsarkiverer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

data class ApplicationState(
		var alive: Boolean = false,
		var ready: Boolean = false,
		var stopping: Boolean = false,
		var busyCounter: Int = 0
)

@Configuration
class AppStateConfig {
	@Bean
	fun appState() = ApplicationState()
}
