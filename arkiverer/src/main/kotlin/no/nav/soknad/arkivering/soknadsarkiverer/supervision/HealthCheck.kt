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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/internal"])
@Unprotected
class HealthCheck(private val appConfiguration: AppConfiguration, private val metrics: ArchivingMetrics) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Hidden
	@GetMapping("/isAlive")
	fun isAlive() = if (applicationIsAlive()) {
		ResponseEntity(HttpStatus.OK)
	} else {
		metrics.setUpOrDown(0.0)
		logger.warn("/isAlive called - application is not alive")
		ResponseEntity("Application is not alive", HttpStatus.INTERNAL_SERVER_ERROR)
	}

	@Hidden
	@GetMapping("/isReady")
	fun isReady(): ResponseEntity<String> {
		return try {
			if (applicationIsReady()) {
				ResponseEntity<String>(HttpStatus.OK)
			} else {
				metrics.setUpOrDown(0.0)
				logger.warn("/isReady called - application is not ready")
				ResponseEntity<String>("Application is not ready", HttpStatus.INTERNAL_SERVER_ERROR)
			}
		} catch (e: Exception) {
			ResponseEntity<String>("Application is not ready: ${e.message}", HttpStatus.INTERNAL_SERVER_ERROR)
		}
	}

	@Hidden
	@GetMapping("/ping")
	fun ping(): ResponseEntity<String> {
		metrics.setUpOrDown(0.0)
		return ResponseEntity("pong", HttpStatus.OK)
	}

	@Hidden
	@GetMapping("/stop")
	fun stop() = runBlocking {
		launch {
			while (isBusy(appConfiguration)) {
				logger.info("Waiting for shutdown")
				delay(2000L)
			}
			logger.info("Pod is now ready for shutdown")
		}
		stop(appConfiguration)
		logger.info("Pod is getting ready for shutdown")
	}


	private fun applicationIsReady() = appConfiguration.state.ready && !appConfiguration.state.stopping

	private fun applicationIsAlive() = appConfiguration.state.alive
}
