package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("/admin")
class ApplicationAdminInterface(private val taskListService: TaskListService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Operation(summary = "Requests that the task with the given key should be rerun. It might take a little while " +
		"before the rerun is started.", tags = ["operations"])
	@ApiResponses(value = [ApiResponse(responseCode = "200", description = "Will always return successfully, but the " +
		"actual rerun will be triggered some time in the future.")])
	@PostMapping("/rerun/{key}")
	fun rerun(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) {
		logger.info("$key: Performing forced rerun")

		taskListService.startPaNytt(key)
	}
}
