package no.nav.soknad.arkivering.soknadsarkiverer.admin

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.security.token.support.core.api.Protected
import no.nav.security.token.support.core.api.Unprotected
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilestorageExistenceResponse
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@Unprotected
@RequestMapping("/admin")
class AdminInterface(private val taskListService: TaskListService,
										 private val fileService: FileserviceInterface,
										 private val joarkService: JournalpostClientInterface,
										 private val kafkaAdminService: KafkaAdminService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostMapping("/rerun/{key}")
	fun rerun(@PathVariable key: String) {
		logger.info("$key: Performing forced rerun")
		GlobalScope.launch { taskListService.pauseAndStart(key) }
	}

	@GetMapping("/kafka/events/allEvents")
	fun allEvents() = kafkaAdminService.getAllEvents()

	@GetMapping("/kafka/events/unfinishedEvents")
	fun unfinishedEvents() = kafkaAdminService.getUnfinishedEvents()

	@GetMapping("/kafka/events/{key}")
	fun specificEvent(@PathVariable key: String) = kafkaAdminService.getAllEventsForKey(key)

	@GetMapping("/kafka/events/eventContent/{messageId}")
	fun eventContent(@PathVariable messageId: String) = kafkaAdminService.content(messageId)

	@GetMapping("/kafka/events/search/{searchPhrase}")
	fun search(@PathVariable searchPhrase: String) = kafkaAdminService.search(searchPhrase.toRegex())

	@GetMapping("/joark/ping")
	fun joarkPing() = joarkService.ping()

	@GetMapping("/fillager/filesExist/{key}")
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
