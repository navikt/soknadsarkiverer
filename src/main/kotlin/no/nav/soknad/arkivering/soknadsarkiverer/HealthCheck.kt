package no.nav.soknad.arkivering.soknadsarkiverer

import no.nav.security.token.support.core.api.Unprotected
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/internal"])
class HealthCheck {
	private val logger = LoggerFactory.getLogger(javaClass)
	private var oppstart = 0

	@GetMapping("/isAlive")
	@Unprotected
	fun isAlive(): String {
		if (oppstart < 10) {
			logger.info("isAlive called")
			oppstart++
		}
		return "Ok"
	}

	@GetMapping("/ping")
	@Unprotected
	fun ping(): String {
		return "pong"
	}

	@GetMapping("/isReady")
	@Unprotected
	fun isReady(): String {
		if (oppstart < 10) {
			logger.debug("isReady called")
			oppstart++
		}
		return "Ready for actions"
	}
}
