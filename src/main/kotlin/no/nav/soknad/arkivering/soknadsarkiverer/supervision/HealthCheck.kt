package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.swagger.v3.oas.annotations.Hidden
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.security.token.support.core.api.Unprotected
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.isBusy
import no.nav.soknad.arkivering.soknadsarkiverer.config.stop
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/internal"])
class HealthCheck(private val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Hidden
	@GetMapping("/isAlive")
	fun isAlive() = "Ok"

	@Hidden
	@GetMapping("/ping")
	fun ping() = "pong"

	@Hidden
	@GetMapping("/isReady")
	fun isReady() = "Ready for actions"

	@Hidden
	@GetMapping("/stop")
	@Unprotected
	fun stop() = runBlocking {
		launch {
			while (isBusy(appConfiguration)) {
				logger.info("Waiting for shutdown")
				delay(2000L)
			}
			logger.info("POD is ready for shutdown")
		}
		stop(appConfiguration)
		logger.info("POD is getting ready for shutdown")
	}
}
