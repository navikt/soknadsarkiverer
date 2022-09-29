package no.nav.soknad.arkivering.soknadsarkiverer.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.admin.FilestorageExistenceStatus.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

@Configuration
class AdminService(private val kafkaAdminConsumer: KafkaAdminConsumer,
									 private val taskListService: TaskListService,
									 private val fileService: FileserviceInterface,
									 private val joarkService: JournalpostClientInterface) : IAdminService {

	private val logger = LoggerFactory.getLogger(javaClass)


	override fun rerun(key: String) {
		logger.info("$key: Performing forced rerun")
		taskListService.startPaNytt(key)
	}

	override fun pingJoark() = joarkService.isReady()

	override fun pingFilestorage() = fileService.ping()

	override fun filesExist(key: String): List<FilestorageExistenceResponse> {
		val soknadarkivschema = taskListService.getSoknadarkivschema(key)
		if (soknadarkivschema == null) {
			logger.warn("$key: Failed to find file ids for given key. The task is probably finished.")
			return listOf(FilestorageExistenceResponse(key, FAILED_TO_FIND_FILE_IDS))
		}

		return try {
			val response = fileService.getFilesFromFilestorage(key, soknadarkivschema)
			response.map { FilestorageExistenceResponse(it.id, if (it.content != null) EXISTS else DOES_NOT_EXIST) }
		} catch (e: FilesAlreadyDeletedException) {
			listOf(FilestorageExistenceResponse(key, DELETED))
		}
	}


	override fun getUnfinishedEvents(timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>> {
		val finishedKeys = getAllFinishedKeys()

		val builder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withFilter { event -> !finishedKeys.contains(event.innsendingKey) }
			.withTimeSelector(timeSelector, timestamp)

		return getAllRequestedEvents(builder)
	}

	override fun getFailedEvents(timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>> {

		val builder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withFilter { it.type == PayloadType.FAILURE }
			.withTimeSelector(timeSelector, timestamp)

		return getAllRequestedEvents(builder)
	}

	override fun getAllEvents(timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>> {
		val builder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withTimeSelector(timeSelector, timestamp)

		return getAllRequestedEvents(builder)
	}

	override fun getEventsByKey(key: String, timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>> {

		val builder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withFilter { it.innsendingKey == key }
			.withTimeSelector(timeSelector, timestamp)

		return getAllRequestedEvents(builder)
	}

	override fun getEventsByRegex(searchPhrase: String, timeSelector: TimeSelector, timestamp: Long): List<KafkaEvent<String>> {

		val builder = EventCollection.Builder()
			.withCapacity(maxNumberOfEventsReturned)
			.withFilter { it.content.toString().contains(searchPhrase.toRegex()) }
			.withTimeSelector(timeSelector, timestamp)

		return getAllRequestedEvents(builder)
	}


	private fun getAllFinishedKeys(): List<String> {
		val processingEventCollectionBuilder = EventCollection.Builder()
			.withoutCapacity()
			.withFilter { it.type == PayloadType.FINISHED }

		return runBlocking { kafkaAdminConsumer.getAllProcessingRecordsAsync(processingEventCollectionBuilder).await() }
			.map { it.innsendingKey }
	}



	internal fun getAllRequestedEvents(builder: EventCollection.Builder): List<KafkaEvent<String>> =
		kafkaAdminConsumer.getAllKafkaRecords(builder)


	internal fun getMetrics(builder: EventCollection.Builder): List<MetricsObject> {

		val records = kafkaAdminConsumer.getProcessingAndMetricsKafkaRecords(builder)
		val mapOfMetrics = records.map {

			val metrics =
				if (it.type == PayloadType.METRIC) {
					val metric = jacksonObjectMapper().readValue(it.content, InnsendingMetrics::class.java)
					Metrics(metric.application, metric.action, metric.startTime, metric.duration)

				} else {
					val processingEvent = jacksonObjectMapper().readValue(it.content, ProcessingEvent::class.java)
					Metrics("soknadsarkiverer", processingEvent.type.name, it.timestamp, -1)
				}

			Pair(it.innsendingKey, metrics)
		}.groupBy({ it.first }, { it.second })


		return mapOfMetrics.map { MetricsObject(it.key, it.value) }
	}

	private fun EventCollection.Builder.withTimeSelector(timeSelector: TimeSelector, timestamp: Long) =
		when (timeSelector) {
			TimeSelector.BEFORE -> this.withEventsBefore(timestamp)
			TimeSelector.AFTER  -> this.withEventsAfter(timestamp)
			TimeSelector.ANY    -> this.withMostRecentEvents()
		}
}

const val maxNumberOfEventsReturned = 50
