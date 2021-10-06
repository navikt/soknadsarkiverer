package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.*

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("/admin")
class ApplicationAdminInterface(private val adminService: IAdminService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@Operation(summary = "Requests that the task with the given key should be rerun. It might take a little while " +
		"before the rerun is started.", tags = ["operations"])
	@ApiResponses(value = [ApiResponse(responseCode = "200", description = "Will always return successfully, but the " +
		"actual rerun will be triggered some time in the future.")])
	@PostMapping("/rerun/{key}")
	fun rerun(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String) {
		logger.debug("Requesting /rerun/$key")

		adminService.rerun(key)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events " +
			"from all topics will be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/allEvents", produces = [APPLICATION_JSON_VALUE])
	fun allEvents(): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/allEvents")

		return adminService.getAllEvents(TimeSelector.ANY, 0)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened before a given " +
		"timestamp.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics " +
			"that happened before a given timestamp will be returned. Any events that happened ON the given timestamp will " +
			"also be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/allEvents/before/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun allEventsBefore(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/allEvents/before/$timestamp")

		return adminService.getAllEvents(TimeSelector.BEFORE, timestamp)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened after a given " +
		"timestamp.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics " +
			"that happened after a given timestamp will be returned. Any events that happened ON the given timestamp will " +
			"also be returned." +
			"\n\n" +
			"An empty list is returned if there are no events on any topics.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/allEvents/after/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun allEventsAfter(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/allEvents/after/$timestamp")

		return adminService.getAllEvents(TimeSelector.AFTER, timestamp)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics, that have not been " +
		"successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events " +
			"from all topics that have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/unfinishedEvents", produces = [APPLICATION_JSON_VALUE])
	fun unfinishedEvents(): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/unfinishedEvents")

		return adminService.getUnfinishedEvents(TimeSelector.ANY, 0)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened before a given " +
		"timestamp, and that have not been successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics " +
			"that happened before a given timestamp, and have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/unfinishedEvents/before/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun unfinishedEventsBefore(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/unfinishedEvents/before/$timestamp")

		return adminService.getUnfinishedEvents(TimeSelector.BEFORE, timestamp)
	}


	@Operation(summary = "Lists $maxNumberOfEventsReturned events from all topics that happened after a given " +
		"timestamp, and that have not been successfully archived.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfEventsReturned events from all topics " +
			"that happened after a given timestamp, and have not been successfully archived will be returned." +
			"\n\n" +
			"An empty list is returned if there are no unfinished events.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/unfinishedEvents/after/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun unfinishedEventsAfter(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/unfinishedEvents/after/$timestamp")

		return adminService.getUnfinishedEvents(TimeSelector.AFTER, timestamp)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from the ProcessingEventLog topic, " +
		"where the status is FAILURE.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events " +
			"from the ProcessingEventLog topic where archiving have failed will be returned." +
			"\n\n" +
			"An empty list is returned if there are no failed events.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/failedEvents", produces = [APPLICATION_JSON_VALUE])
	fun failedEvents(): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/failedEvents")

		return adminService.getFailedEvents(TimeSelector.ANY, 0)
	}

	@Operation(summary = "Lists the $maxNumberOfEventsReturned events from the ProcessingEventLog topic that happened " +
		"before a given timestamp, where the status is FAILURE.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from the " +
			"ProcessingEventLog topic that happened before a given timestamp, where archiving have failed will be returned." +
			"\n\n" +
			"An empty list is returned if there are no failed events.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/failedEvents/before/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun failedEventsBefore(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/failedEvents/before/$timestamp")

		return adminService.getFailedEvents(TimeSelector.BEFORE, timestamp)
	}

	@Operation(summary = "Lists the $maxNumberOfEventsReturned events from the ProcessingEventLog topic that happened " +
		"after a given timestamp, where the status is FAILURE.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from the " +
			"ProcessingEventLog topic that happened after a given timestamp, where archiving have failed will be returned." +
			"\n\n" +
			"An empty list is returned if there are no failed events.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/failedEvents/after/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun failedEventsAfter(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/failedEvents/after/$timestamp")

		return adminService.getFailedEvents(TimeSelector.AFTER, timestamp)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned most recent events from all topics that have a " +
		"given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned most recent events " +
			"from all topics that have a given key. An empty list is returned if the key is not found.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/key/{key}", produces = [APPLICATION_JSON_VALUE])
	fun specificEvent(
		@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/key/$key")

		return adminService.getEventsByKey(key, TimeSelector.ANY, 0)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned events before the given timestamp from all topics " +
		"that have a given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from " +
			"all topics that have a given key, and that occurred before the given timestamp. Any events that happened " +
			"ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if the key is not found.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/key/before/{timestamp}/{key}", produces = [APPLICATION_JSON_VALUE])
	fun specificEventBefore(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
		@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/key/before/$timestamp/$key")

		return adminService.getEventsByKey(key, TimeSelector.BEFORE, timestamp)
	}


	@Operation(summary = "Lists the $maxNumberOfEventsReturned events after the given timestamp from all topics " +
		"that have a given key.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from " +
			"all topics that have a given key, and that occurred after the given timestamp. Any events that happened " +
			"ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if the key is not found.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/key/after/{timestamp}/{key}", produces = [APPLICATION_JSON_VALUE])
	fun specificEventAfter(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
		@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/key/after/$timestamp/$key")

		return adminService.getEventsByKey(key, TimeSelector.AFTER, timestamp)
	}


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search " +
		"phrase will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards. Will return " +
		"the $maxNumberOfEventsReturned most recent matching events.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from all topics " +
			"that matches the search phrase will be returned. An empty list is returned if there are no events matching " +
			"the search phrase.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/search/{searchPhrase}", produces = [APPLICATION_JSON_VALUE])
	fun search(
		@Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/search/$searchPhrase")

		return adminService.getEventsByRegex(searchPhrase, TimeSelector.ANY, 0)
	}


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search " +
		"phrase will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards. Will return " +
		"the $maxNumberOfEventsReturned events before the given timestamp, that match the search phrase", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from all topics " +
			"that matches the search phrase, and that occurred before the given timestamp will be returned. Any events " +
			"that happened ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if there are no events matching the search phrase.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/search/before/{timestamp}/{searchPhrase}", produces = [APPLICATION_JSON_VALUE])
	fun searchBefore(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
		@Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/search/before/$timestamp/$searchPhrase")

		return adminService.getEventsByRegex(searchPhrase, TimeSelector.BEFORE, timestamp)
	}


	@Operation(summary = "Searches so that only events that matches the given search phrase are returned. The search " +
		"phrase will be converted to a Kotlin Regex, to allow more advanced searching with e.g. wildcards. Will return " +
		"the $maxNumberOfEventsReturned events after the given timestamp, that match the search phrase.", tags = ["events"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfEventsReturned events from all topics " +
			"that matches the search phrase, and that occurred after the given timestamp will be returned. Any events that " +
			"happened ON the given timestamp will also be returned." +
			"\n\n" +
			"An empty list is returned if there are no events matching the search phrase.", content = [
			Content(mediaType = APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = KafkaEvent::class)))])])
	@GetMapping("/kafka/events/search/after/{timestamp}/{searchPhrase}", produces = [APPLICATION_JSON_VALUE])
	fun searchAfter(
		@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long,
		@Parameter(description = "Search phrase (Regex)") @PathVariable searchPhrase: String
	): List<KafkaEvent<String>> {
		logger.debug("Requesting /kafka/events/search/after/$timestamp/$searchPhrase")

		return adminService.getEventsByRegex(searchPhrase, TimeSelector.AFTER, timestamp)
	}


	@Operation(summary = "Pings Joark to see if it is up.", tags = ["ping"])
	@GetMapping("/joark/ping")
	fun pingJoark() = adminService.pingJoark()


	@Operation(summary = "Pings Filestorage to see if it is up.", tags = ["ping"])
	@GetMapping("/fillager/ping")
	fun pingFilestorage() = adminService.pingFilestorage()


	@Operation(summary = "A Soknadsarkivschema can have several files associated in the Filestorage. Given a key to a " +
		"Soknadsarkivschema, the application will do a lookup to Filestorage for each file, to see if it " +
		"exists.", tags = ["lookup"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list will be returned, mapping Filestorage keys to its " +
			"status. The list will consist of Filestorage keys and whether that file is in the Filestorage or not." +
			"\n\n" +
			"In case the Filestorage keys could not be looked up (most likely because the task is already finished and " +
			"archived to Joark), the returned list will consist of one element and an error message explaining that " +
			"Filestorage keys could not be found.", content = [
			(Content(mediaType = APPLICATION_JSON_VALUE, array = (ArraySchema(schema = Schema(implementation = FilestorageExistenceResponse::class)))))])])
	@GetMapping("/fillager/filesExist/{key}", produces = [APPLICATION_JSON_VALUE])
	fun filesExists(
		@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String
	): List<FilestorageExistenceResponse> {
		logger.debug("Requesting /fillager/filesExist/$key")

		return adminService.filesExist(key)
	}
}
