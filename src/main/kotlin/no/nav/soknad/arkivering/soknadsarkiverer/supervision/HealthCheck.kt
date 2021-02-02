package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.swagger.v3.oas.annotations.Hidden
import kotlinx.coroutines.*
import no.nav.security.token.support.core.api.Unprotected
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.config.isBusy
import no.nav.soknad.arkivering.soknadsarkiverer.config.stop
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpServerErrorException

@RestController
@RequestMapping(value = ["/internal"])
@Unprotected
class HealthCheck(private val appConfiguration: AppConfiguration,
									private val fileService: FileserviceInterface,
									private val joarkService: JournalpostClientInterface,
									private val metrics: ArchivingMetrics) {

	private val logger = LoggerFactory.getLogger(javaClass)

	@Hidden
	@GetMapping("/isAlive")
	fun isAlive() = if (applicationIsAlive()) {
		"Ok"
	} else {
		metrics.setUpOrDown(0.0)
		logger.warn("/isAlive called - application is not alive")
		throwException()
	}

	@Hidden
	@GetMapping("/isReady")
	fun isReady() = if (applicationIsReady()) {
		"Ready for actions"
	} else {
		metrics.setUpOrDown(0.0)
		logger.warn("/isReady called - application is not ready")
		throwException()
	}

	@Hidden
	@GetMapping("/ping")
	fun ping(): String {
		val dependencies = listOf(
			Dependency({ fileService.ping() }, "pong", "FileStorage"),
			Dependency({ joarkService.isAlive() }, "Application is alive!", "Joark")
		)
		metrics.setUpOrDown(0.0)
		throwExceptionIfDependenciesAreDown(dependencies)

		logger.info("/ping called")
		return "pong"
	}

	@Hidden
	@GetMapping("/stop")
	fun stop() = runBlocking {
		launch {
			while (isBusy(appConfiguration)) {
				logger.info("Waiting for shutdown")
				delay(2000L)
			}
			logger.info("Pod is ready for shutdown")
		}
		stop(appConfiguration)
		logger.info("Pod is getting ready for shutdown")
	}


	private fun applicationIsReady(): Boolean {
		val dependencies = listOf(
			Dependency({ fileService.isReady() }, "Holla, si Ready", "FileStorage"),
			Dependency({ joarkService.isAlive() }, "Application is alive!", "Joark")
		)
		throwExceptionIfDependenciesAreDown(dependencies)

		return appConfiguration.state.ready && !appConfiguration.state.stopping
	}

	/**
	 * Will throw exception if any of the dependencies are not returning the expected value.
	 * If all is well, the function will silently exit.
	 */
	private fun throwExceptionIfDependenciesAreDown(applications: List<Dependency>) {
		runBlocking {
			applications
				.map { Triple(GlobalScope.async { it.dependencyEndpoint.invoke() }, it.expectedResponse, it.dependencyName) }
				.forEach { if (it.first.await() != it.second) throwException("${it.third} does not seem to be up") }
		}
	}

	private fun throwException(message: String? = null): String {
		val status = HttpStatus.INTERNAL_SERVER_ERROR
		throw HttpServerErrorException(status, message ?: status.name)
	}

	private fun applicationIsAlive() = appConfiguration.state.up


	private data class Dependency(val dependencyEndpoint: () -> String,
																val expectedResponse: String,
																val dependencyName: String)
}
