package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import no.nav.security.token.support.core.api.Unprotected
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/internal"])
class HealthCheck {

	@GetMapping("/isAlive")
	@Unprotected
	fun isAlive() = "Ok"

	@GetMapping("/ping")
	@Unprotected
	fun ping() = "pong"

	@GetMapping("/isReady")
	@Unprotected
	fun isReady() = "Ready for actions"
}
