package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.swagger.v3.oas.annotations.Hidden
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.security.token.support.core.api.Unprotected
import no.nav.soknad.arkivering.api.HealthApi
import no.nav.soknad.arkivering.model.ApplicationStatus
import no.nav.soknad.arkivering.model.ApplicationStatusType
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.config.isBusy
import no.nav.soknad.arkivering.soknadsarkiverer.config.stop
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Unprotected
class HealthCheck(private val applicationState: ApplicationState, private val metrics: ArchivingMetrics, @Value("\${status_log_url}") private val statusLogUrl: String): HealthApi {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Hidden
	@GetMapping("internal/isAlive")
	fun isAlive() = if (applicationIsAlive()) {
		ResponseEntity(HttpStatus.OK)
	} else {
		metrics.setUpOrDown(0.0)
		logger.warn("/isAlive called - application is not alive")
		ResponseEntity("Application is not alive", HttpStatus.INTERNAL_SERVER_ERROR)
	}

	@Hidden
	@GetMapping("internal/isReady")
	fun isReady(): ResponseEntity<String> {
		return try {
			if (applicationIsReady()) {
				ResponseEntity(HttpStatus.OK)
			} else {
				metrics.setUpOrDown(0.0)
				logger.warn("/isReady called - application is not ready")
				ResponseEntity("Application is not ready", HttpStatus.INTERNAL_SERVER_ERROR)
			}
		} catch (e: Exception) {
			ResponseEntity("Application is not ready: ${e.message}", HttpStatus.INTERNAL_SERVER_ERROR)
		}
	}

	@Hidden
	@GetMapping("internal/ping")
	fun ping(): ResponseEntity<String> {
		metrics.setUpOrDown(0.0)
		return ResponseEntity("pong", HttpStatus.OK)
	}

	@Hidden
	@GetMapping("internal/stop")
	fun stop() = runBlocking {
		launch {
			while (isBusy(applicationState)) {
				logger.info("Waiting for shutdown")
				delay(2000L)
			}
			logger.info("Pod is now ready for shutdown")
		}
		stop(applicationState)
		logger.info("Pod is getting ready for shutdown")
	}

	override fun getStatus(): ResponseEntity<ApplicationStatus> {
		return ResponseEntity(
			ApplicationStatus(status = ApplicationStatusType.OK, description = "OK", logLink = statusLogUrl),
			HttpStatus.OK
		)
	}

	private fun applicationIsReady() = applicationState.ready && !applicationState.stopping

	private fun applicationIsAlive() = applicationState.alive
}
