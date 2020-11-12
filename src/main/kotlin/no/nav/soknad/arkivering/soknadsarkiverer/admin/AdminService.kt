package no.nav.soknad.arkivering.soknadsarkiverer.admin

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.avroschemas.EventTypes.FINISHED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.dto.FilestorageExistenceResponse
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.time.ZoneOffset

@Configuration
class AdminService(private val kafkaAdminConsumer: KafkaAdminConsumer,
									 private val taskListService: TaskListService,
									 private val fileService: FileserviceInterface,
									 private val joarkService: JournalpostClientInterface) {

	private val logger = LoggerFactory.getLogger(javaClass)


	fun rerun(key: String) {
		logger.info("$key: Performing forced rerun")
		GlobalScope.launch { taskListService.pauseAndStart(key) }
	}

	fun pingJoark() = joarkService.ping()

	fun pingFilestorage() = fileService.ping()

	fun filesExist(key: String): List<FilestorageExistenceResponse> {
		val soknadarkivschema = taskListService.getSoknadarkivschema(key)
		if (soknadarkivschema == null) {
			logger.warn("$key: Failed to find file ids for given key. The task is probably finished.")
			return listOf(FilestorageExistenceResponse(key, "$key: Failed to find file ids for given key. The task is probably finished."))
		}

		val response = fileService.getFilesFromFilestorage(key, soknadarkivschema)
		return response.map { FilestorageExistenceResponse(it.uuid, if (it.fil != null) "Exists" else "Does not exist") }
	}


	internal fun getUnfinishedEvents(builder: EventCollection.Builder): List<KafkaEvent> {
		val finishedKeys = getAllFinishedKeys()
		builder.withFilter { event -> !finishedKeys.contains(event.key) }

		return getAllRequestedEvents(builder)
	}

	private fun getAllFinishedKeys(): List<String> {
		val processingEventCollectionBuilder = EventCollection.Builder()
			.withoutCapacity()
			.withFilter { (it as KafkaEventRaw<ProcessingEvent>).payload.getType() == FINISHED }

		return runBlocking { kafkaAdminConsumer.getAllProcessingRecordsAsync(processingEventCollectionBuilder).await() }
			.map { it.key }
	}


	@Deprecated("Event content should be returned as part of the KafkaEvent")
	internal fun content(builder: EventCollection.Builder): String {

		val event = kafkaAdminConsumer.getAllKafkaRecords(builder).firstOrNull()
		if (event != null)
			return event.payload.toString()

		throw NoSuchElementException("Could not find message with id")
	}


	internal fun getAllRequestedEvents(builder: EventCollection.Builder): List<KafkaEvent> =
		createContentEventList(kafkaAdminConsumer.getAllKafkaRecords(builder))

	private fun createContentEventList(events: List<KafkaEventRaw<*>>): List<KafkaEvent> {

		val sequence = generateSequence(0) { it + 1 }
			.take(events.size).toList()

		return events
			.sortedBy { it.timestamp }
			.zip(sequence) { event, seq -> KafkaEvent(seq, event.key, event.messageId, getTypeRepresentation(event.payload), event.timestamp.toInstant(ZoneOffset.UTC).toEpochMilli()) }
	}

	private fun getTypeRepresentation(data: Any?): String {
		return when(data) {
			is ProcessingEvent -> data.getType().name
			is Soknadarkivschema -> "INPUT"
			is String -> {
				"MESSAGE " + when {
					data.startsWith("ok", true) -> "Ok"
					data.startsWith("Exception", true) -> "Exception"
					else -> "Unknown"
				}
			}
			else -> "UNKNOWN"
		}
	}
}
