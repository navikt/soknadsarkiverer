package no.nav.soknad.arkivering.soknadsarkiverer.admin

import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.web.bind.annotation.*

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("/admin")
class AdminInterface(private val kafkaAdminService: KafkaAdminService) {

	@PostMapping("/rerun/{key}")
	fun rerun(@PathVariable key: String) = kafkaAdminService.rerun(key)

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
	fun pingJoark() = kafkaAdminService.pingJoark()

	@GetMapping("/fillager/ping")
	fun pingFilestorage() = kafkaAdminService.pingFilestorage()

	@GetMapping("/fillager/filesExist/{key}")
	fun filesExists(@PathVariable key: String) = kafkaAdminService.filesExist(key)
}
