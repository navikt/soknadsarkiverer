package no.nav.soknad.arkivering.soknadsarkiverer

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/internal"])
class IsAlive {
	private val logger = LoggerFactory.getLogger(javaClass)
	private var oppstart = 0

	@GetMapping("/isAlive")
	fun isAlive(): String {
		if (oppstart < 10) {
			logger.info("isAlive called")
			oppstart++
		}
		return "Ok"
	}

	@GetMapping("/ping")
	fun ping(): String {
		return "pong"
	}

	@GetMapping("/isReady")
	fun isReady(): String {
		if (oppstart < 10) {
			logger.debug("isReady called")
			oppstart++
		}
		return "Ready for actions"
	}
}
