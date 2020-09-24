package no.nav.soknad.arkivering.soknadsarkiverer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
class SoknadsarkivererApplication

fun main(args: Array<String>) {
	runApplication<SoknadsarkivererApplication>(*args)
}
