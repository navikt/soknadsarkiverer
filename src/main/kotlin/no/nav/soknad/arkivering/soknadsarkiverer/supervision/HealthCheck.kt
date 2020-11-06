package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import no.nav.security.token.support.core.api.Unprotected
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
//@Unprotected
@RequestMapping(value = ["/internal"])
class HealthCheck {

	@GetMapping("/isAlive")
	fun isAlive() = "Ok"

	@GetMapping("/ping")
	fun ping() = "pong"

	@GetMapping("/isReady")
	fun isReady() = "Ready for actions"
}
