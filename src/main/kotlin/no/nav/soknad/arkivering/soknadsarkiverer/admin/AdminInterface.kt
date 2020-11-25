package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.web.bind.annotation.*

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("/admin")
class AdminInterface(private val adminService: AdminService) {

	@Operation(summary = "Requests that the task with the given key should be rerun. It might take a little while before " +
		"the rerun is started.", tags = ["operations"])
	@ApiResponses(value = [ApiResponse(responseCode = "200", description = "Will always return successfully, but the " +
		"actual rerun will be triggered some time in the future.")])
	@PostMapping("/rerun/{key}")
	fun rerun(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) = adminService.rerun(key)


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events from " +
			"all topics will be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/allEvents")
	fun allEvents(): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withMostRecentEvents()

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened before a given timestamp.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened before a given timestamp will be returned. Any events that happened ON the given timestamp will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/allEvents/before/{timestamp}")
	fun allEventsBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsBefore(timestamp)

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened after a given timestamp.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened after a given timestamp will be returned. Any events that happened ON the given timestamp will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/allEvents/after/{timestamp}")
	fun allEventsAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsAfter(timestamp)

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics, that have not been " +
		"successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events from " +
			"all topics that have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/unfinishedEvents")
	fun unfinishedEvents(): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withMostRecentEvents()

		return adminService.getUnfinishedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened before a given " +
		"timestamp, and that have not been successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened before a given timestamp, and have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/unfinishedEvents/before/{timestamp}")
	fun unfinishedEventsBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsBefore(timestamp)

		return adminService.getUnfinishedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened after a given " +
		"timestamp, and that have not been successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics that " +
			"happened after a given timestamp, and have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/unfinishedEvents/after/{timestamp}")
	fun unfinishedEventsAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsAfter(timestamp)

		return adminService.getUnfinishedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics that have a given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events from " +
			"all topics that have a given key. An empty list is returned if the key is not found.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/key/{key}")
	fun specificEvent(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withMostRecentEvents()
			.withFilter { it.innsendingKey == key }

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned events before the given timestamp from all topics " +
		"that have a given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from " +
			"all topics that have a given key, and that occurred before the given timestamp. Any events that happened " +
			"ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if the key is not found.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/key/before/{timestamp}/{key}")
	fun specificEventBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
													@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsBefore(timestamp)
			.withFilter { it.innsendingKey == key }

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned events after the given timestamp from all topics " +
		"that have a given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from " +
			"all topics that have a given key, and that occurred after the given timestamp. Any events that happened " +
			"ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if the key is not found.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/key/after/{timestamp}/{key}")
	fun specificEventAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
												 @Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsAfter(timestamp)
			.withFilter { it.innsendingKey == key }

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search phrase " +
		"will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards. Will return the " +
		"$maxNumberOfEventsReturned most recent matching events.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from all topics " +
			"that matches the search phrase will be returned. An empty list is returned if there are no events matching the " +
			"search phrase.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/search/{searchPhrase}")
	fun search(@Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withMostRecentEvents()
			.withFilter { it.content.toString().contains(searchPhrase.toRegex()) }

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search phrase " +
		"will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards. Will return the " +
		"$maxNumberOfEventsReturned events before the given timestamp, that match the search phrase.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from all topics " +
			"that matches the search phrase, and that occurred before the given timestamp will be returned. Any events that " +
			"happened ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if there are no events matching the search phrase.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/search/before/{timestamp}/{searchPhrase}")
	fun searchBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
									 @Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsBefore(timestamp)
			.withFilter { it.content.toString().contains(searchPhrase.toRegex()) }

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search phrase " +
		"will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards. Will return the " +
		"$maxNumberOfEventsReturned events after the given timestamp, that match the search phrase.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from all topics " +
			"that matches the search phrase, and that occurred after the given timestamp will be returned. Any events that " +
			"happened ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if there are no events matching the search phrase.", content = [
			(Content(mediaType = "application/json", array = (ArraySchema(schema = Schema(implementation = KafkaEvent::class)))))])])
	@GetMapping("/kafka/events/search/after/{timestamp}/{searchPhrase}")
	fun searchAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
									@Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String): List<KafkaEvent<String>> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withEventsAfter(timestamp)
			.withFilter { it.content.toString().contains(searchPhrase.toRegex()) }

		return adminService.getAllRequestedEvents(eventCollectionBuilder)
	}


	@Operation(summary = "Pings Joark to see if it is up.", tags = ["ping"])
	@GetMapping("/joark/ping")
	fun pingJoark() = adminService.pingJoark()


	@Operation(summary = "Pings Filestorage to see if it is up.", tags = ["ping"])
	@GetMapping("/fillager/ping")
	fun pingFilestorage() = adminService.pingFilestorage()


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
	fun filesExists(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) = adminService.filesExist(key)
}

const val maxNumberOfEventsReturned = 50
