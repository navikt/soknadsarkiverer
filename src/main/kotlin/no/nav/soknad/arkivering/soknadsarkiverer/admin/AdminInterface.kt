package no.nav.soknad.arkivering.soknadsarkiverer.admin

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.security.token.support.core.api.Protected
import no.nav.security.token.support.core.api.Unprotected
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilestorageExistenceResponse
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/admin")
class AdminInterface(private val taskListService: TaskListService, private val fileService: FileserviceInterface) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostMapping("/rerun/{key}")
	@Unprotected
	fun rerun(@PathVariable key: String) {
		logger.info("$key: Performing forced rerun")
		GlobalScope.launch { taskListService.pauseAndStart(key) }
	}

	@GetMapping("/kafka/events/allEvents")
	@Unprotected
	fun allEvents() {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/unfinishedEvents")
	@Unprotected
	fun unfinishedEvents() {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/{key}")
	@Unprotected
	fun specificEvent(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/eventContent/{key}")
	@Unprotected
	fun eventContent(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/fillager/filesExist/{key}")
	@Protected
	fun filesExists(@PathVariable key: String): List<FilestorageExistenceResponse> {
		val soknadarkivschema = taskListService.getSoknadarkivschema(key)
		if (soknadarkivschema == null) {
			logger.warn("$key: Failed to find file ids for given key. The task is probably finished.")
			throw ResponseStatusException(HttpStatus.NOT_FOUND, "File ids for key $key not found")
		}

		val response = fileService.getFilesFromFilestorage(key, soknadarkivschema)
		return response.map { FilestorageExistenceResponse(it.uuid, if (it.fil != null) "Exists" else "Does not exist") }
	}
}
