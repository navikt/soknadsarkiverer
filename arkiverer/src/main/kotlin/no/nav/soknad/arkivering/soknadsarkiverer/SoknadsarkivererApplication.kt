package no.nav.soknad.arkivering.soknadsarkiverer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SoknadsarkivererApplication

fun main(args: Array<String>) {
	runApplication<SoknadsarkivererApplication>(*args)
}
