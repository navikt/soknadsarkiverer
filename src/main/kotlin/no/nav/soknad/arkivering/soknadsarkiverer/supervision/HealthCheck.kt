package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.swagger.v3.oas.annotations.Hidden
import no.nav.security.token.support.core.api.Unprotected
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/internal"])
class HealthCheck {

	@Hidden
	@GetMapping("/isAlive")
	@Unprotected
	fun isAlive() = "Ok"

	@Hidden
	@GetMapping("/ping")
	@Unprotected
	fun ping() = "pong"

	@Hidden
	@GetMapping("/isReady")
	@Unprotected
	fun isReady() = "Ready for actions"
}
