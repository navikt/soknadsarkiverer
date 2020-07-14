package no.nav.soknad.arkivering.soknadsarkiverer.admin

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin")
class AdminInterface(private val taskListService: TaskListService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostMapping("/rerun/{key}")
	fun rerun(@PathVariable key: String) {
		logger.info("$key: Performing forced rerun")
		GlobalScope.launch { taskListService.pauseAndStart(key) }
	}

	@GetMapping("/kafka/events/allEvents")
	fun allEvents() {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/unfinishedEvents")
	fun unfinishedEvents() {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/{key}")
	fun specificEvent(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/kafka/events/eventContent/{key}")
	fun eventContent(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}

	@GetMapping("/fillager/filesExist/{key}")
	fun filesExists(@PathVariable key: String) {
		//TODO("Not yet implemented")
	}
}
