package no.nav.soknad.arkivering.soknadsarkiverer.admin

import no.nav.security.token.support.core.api.Unprotected
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin")
class AdminInterface(private val kafkaAdminService: KafkaAdminService) {

	@PostMapping("/rerun/{key}")
	@Unprotected
	fun rerun(@PathVariable key: String) = kafkaAdminService.rerun(key)

	@GetMapping("/kafka/events/allEvents")
	@Unprotected
	fun allEvents() = kafkaAdminService.getAllEvents()

	@GetMapping("/kafka/events/unfinishedEvents")
	@Unprotected
	fun unfinishedEvents() = kafkaAdminService.getUnfinishedEvents()

	@GetMapping("/kafka/events/{key}")
	@Unprotected
	fun specificEvent(@PathVariable key: String) = kafkaAdminService.getAllEventsForKey(key)

	@GetMapping("/kafka/events/eventContent/{messageId}")
	@Unprotected
	fun eventContent(@PathVariable messageId: String) = kafkaAdminService.content(messageId)

	@GetMapping("/kafka/events/search/{searchPhrase}")
	@Unprotected
	fun search(@PathVariable searchPhrase: String) = kafkaAdminService.search(searchPhrase.toRegex())

	@GetMapping("/joark/ping")
	@Unprotected
	fun pingJoark() = kafkaAdminService.pingJoark()

	@GetMapping("/fillager/ping")
	@Unprotected
	fun pingFilestorage() = kafkaAdminService.pingFilestorage()

	@GetMapping("/fillager/filesExist/{key}")
	@Unprotected
	fun filesExists(@PathVariable key: String) = kafkaAdminService.filesExist(key)
}
