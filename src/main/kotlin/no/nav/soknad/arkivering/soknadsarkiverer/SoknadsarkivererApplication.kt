package no.nav.soknad.arkivering.soknadsarkiverer

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan("no.nav.soknad.arkivering", "no.nav.security.token")
class SoknadsarkivererApplication

private val logger = LoggerFactory.getLogger(SoknadsarkivererApplication::class.java)

fun main(args: Array<String>) {
	val discovery = System.getenv("DISCOVERYURL")
	val tokenurl = System.getenv("TOKENENDPOINTURL")
	logger.info("SoknadsarkivererApplication: discoveryurl=$discovery, tokenurl=$tokenurl")
	runApplication<SoknadsarkivererApplication>(*args)
}
