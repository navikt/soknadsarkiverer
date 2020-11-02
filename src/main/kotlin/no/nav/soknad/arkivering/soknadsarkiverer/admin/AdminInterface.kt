package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import no.nav.security.token.support.core.api.Unprotected
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilestorageExistenceResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin")
class AdminInterface(private val kafkaAdminService: KafkaAdminService) {

	@Operation(summary = "Requests that the task with the given key should be rerun. It might take a little while before " +
		"the rerun is started.", tags = ["operations"])
	@ApiResponses(value = [ApiResponse(responseCode = "200", description = "Will always return successfully, but the " +
		"actual rerun will be triggered some time in the future.")])
	@PostMapping("/rerun/{key}")
	@Unprotected
	fun rerun(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) = kafkaAdminService.rerun(key)


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events from " +
			"all topics will be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/allEvents")
	@Unprotected
	fun allEvents() = kafkaAdminService.getAllEvents()


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened before a given timestamp.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened before a given timestamp will be returned. Any events that happened ON the given timestamp will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/allEvents/before/{timestamp}")
	@Unprotected
	fun allEventsBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent> = TODO("Not implemented yet")


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened after a given timestamp.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened after a given timestamp will be returned. Any events that happened ON the given timestamp will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/allEvents/after/{timestamp}")
	@Unprotected
	fun allEventsAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent> = TODO("Not implemented yet")


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics, that have not been " +
		"successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events from " +
			"all topics that have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/unfinishedEvents")
	@Unprotected
	fun unfinishedEvents() = kafkaAdminService.getUnfinishedEvents()


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened before a given " +
		"timestamp, and that have not been successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened before a given timestamp, and have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/unfinishedEvents/before/{timestamp}")
	@Unprotected
	fun unfinishedEventsBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent> = TODO("Not implemented yet")


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened after a given " +
		"timestamp, and that have not been successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened after a given timestamp, and have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/unfinishedEvents/after/{timestamp}")
	@Unprotected
	fun unfinishedEventsAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent> = TODO("Not implemented yet")


	@Operation(summary = "Lists all events from all topics that have a given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of all events from all topics that have a given key. An " +
			"empty list is returned if the key is not found.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/{key}")
	@Unprotected
	fun specificEvent(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) = kafkaAdminService.getAllEventsForKey(key)


	@Operation(summary = "Returns a string representation of a given event. Every event on every topic has a unique " +
		"messageId, and by providing that messageId, the content of the event is returned.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A string representation of the event with the given messageId.", content = [
			(Content(mediaType = "application/plain", schema = Schema(implementation = String::class)))])])
	@GetMapping("/kafka/events/eventContent/{messageId}")
	@Unprotected
	fun eventContent(@Parameter(description = "messageId of event to get content for.") @PathVariable messageId: String) = kafkaAdminService.content(messageId)


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search phrase " +
		"will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of all events from all topics that matches the search phrase " +
			"will be returned. An empty list is returned if there ar no events matching the search phrase.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/search/{searchPhrase}")
	@Unprotected
	fun search(@Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String) = kafkaAdminService.search(searchPhrase.toRegex())


	@Operation(summary = "Pings Joark to see if it is up.", tags = ["ping"])
	@GetMapping("/joark/ping")
	@Unprotected
	fun pingJoark() = kafkaAdminService.pingJoark()


	@Operation(summary = "Pings Filestorage to see if it is up.", tags = ["ping"])
	@GetMapping("/fillager/ping")
	@Unprotected
	fun pingFilestorage() = kafkaAdminService.pingFilestorage()


	@Operation(summary = "A Soknadsarkivschema can have several files associated in the Filestorage. Given a key to a " +
		"Soknadsarkivschema, the application will do a lookup to Filestorage for each file, to see if it exists.", tags = ["lookup"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list will be returned, mapping Filestorage keys to its status. " +
			"The list will consist of Filestorage keys and whether that file is in the Filestorage or not." +
			"\n\n" +
			"In case the Filestorage keys could not be looked up (most likely because the task is already finished and archived " +
			"to Joark), the returned list will consist of one element and an error message explaining that Filestorage keys " +
			"could not be found.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = FilestorageExistenceResponse::class)))))])])
	@GetMapping("/fillager/filesExist/{key}")
	@Unprotected
	fun filesExists(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) = kafkaAdminService.filesExist(key)
}

const val maxNumberOfEventsReturned = 50
