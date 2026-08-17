package no.nav.soknad.arkivering.soknadsarkiverer

import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.context.annotation.Bean

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class SoknadsarkivererApplication

@Bean
fun appState() = ApplicationState()

fun main(args: Array<String>) {
	runApplication<SoknadsarkivererApplication>(*args)
}
