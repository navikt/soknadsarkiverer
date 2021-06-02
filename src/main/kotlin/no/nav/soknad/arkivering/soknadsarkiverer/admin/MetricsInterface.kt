package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import no.nav.security.token.support.core.api.Unprotected
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Unprotected
@RequestMapping
class MetricsInterface(private val adminService: AdminService) {

	@Operation(summary = "Returns metrics", tags = ["metrics"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of the $maxNumberOfMetricsReturned most recent metrics", content = [
			(Content(mediaType = APPLICATION_JSON_VALUE, array = (ArraySchema(schema = Schema(implementation = MetricsObject::class)))))])])
	@GetMapping("/metrics", produces = [APPLICATION_JSON_VALUE])
	fun metrics(): List<MetricsObject> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfMetricsReturned)
			.withMostRecentEvents()

		return adminService.getMetrics(eventCollectionBuilder)
	}

	@Operation(summary = "Lists $maxNumberOfEventsReturned metrics that happened before a given timestamp.", tags = ["metrics"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfMetricsReturned metrics that " +
			"happened before a given timestamp will be returned. Any metrics that happened ON the given timestamp will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no metrics.", content = [
			(Content(mediaType = APPLICATION_JSON_VALUE, array = (ArraySchema(schema = Schema(implementation = MetricsObject::class)))))])])
	@GetMapping("/metrics/before/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun allEventsBefore(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<MetricsObject> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfMetricsReturned)
			.withEventsBefore(timestamp)

		return adminService.getMetrics(eventCollectionBuilder)
	}

	@Operation(summary = "Lists $maxNumberOfEventsReturned metrics that happened after a given timestamp.", tags = ["metrics"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfMetricsReturned metrics that " +
			"happened after a given timestamp will be returned. Any metrics that happened ON the given timestamp will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no metrics.", content = [
			(Content(mediaType = APPLICATION_JSON_VALUE, array = (ArraySchema(schema = Schema(implementation = MetricsObject::class)))))])])
	@GetMapping("/metrics/after/{timestamp}", produces = [APPLICATION_JSON_VALUE])
	fun allEventsAfter(@Parameter(description = "Timestamp (milliseconds since epoch)") @PathVariable timestamp: Long): List<MetricsObject> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfMetricsReturned)
			.withEventsAfter(timestamp)

		return adminService.getMetrics(eventCollectionBuilder)
	}

	@Operation(summary = "Lists $maxNumberOfEventsReturned metrics that happened between two given timestamps.", tags = ["metrics"])
	@ApiResponses(value = [
		ApiResponse(responseCode = "200", description = "A list of $maxNumberOfMetricsReturned metrics that " +
			"happened between two given timestamps will be returned. Any metrics that happened ON the given timestamps will also " +
			"be returned." +
			"\n\n" +
			"An empty list is returned if there are no metrics.", content = [
			(Content(mediaType = APPLICATION_JSON_VALUE, array = (ArraySchema(schema = Schema(implementation = MetricsObject::class)))))])])
	@GetMapping("/metrics/between/{starttime}/{endtime}", produces = [APPLICATION_JSON_VALUE])
	fun allEventsBetween(@Parameter(description = "Timestamp of the start time (milliseconds since epoch)") @PathVariable starttime: Long,
											 @Parameter(description = "Timestamp of the end time (milliseconds since epoch)") @PathVariable endtime: Long): List<MetricsObject> {

		val eventCollectionBuilder = EventCollection.Builder()
			.withCapacity(maxNumberOfMetricsReturned)
			.withEventsAfter(starttime)

		val metrics = adminService.getMetrics(eventCollectionBuilder)

		return metrics.map {
			MetricsObject(it.key, it.datapoints.filter { datapoint -> datapoint.startTime <= endtime })
		}.filter { it.datapoints.isNotEmpty() }
	}
}

const val maxNumberOfMetricsReturned = 50_000
