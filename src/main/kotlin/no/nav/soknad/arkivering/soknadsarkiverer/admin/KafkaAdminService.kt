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
class KafkaAdminService(private val kafkaAdminConsumer: KafkaAdminConsumer,
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


	fun getAllEvents() = getAllEvents { true }

	fun getUnfinishedEvents(): List<KafkaEvent> {
		val processingEvents = runBlocking { kafkaAdminConsumer.getAllProcessingRecordsAsync().await() }
		val events = runBlocking {
			listOf(kafkaAdminConsumer.getAllInputRecordsAsync().await(), kafkaAdminConsumer.getAllMessageRecordsAsync().await(), processingEvents)
				.flatten()
		}

		val finishedKeys = processingEvents
			.filter { (_, _, _, processingEvent) -> processingEvent.getType() == FINISHED }
			.map { (key, _, _, _) -> key }

		return createContentEventList(events) { event -> !finishedKeys.contains(event.key) }
	}

	fun getAllEventsForKey(key: String) = getAllEvents { it.key == key }

	fun search(searchPhrase: Regex) = getAllEvents { it.payload.toString().contains(searchPhrase) }

	fun content(messageId: String): String {
		val event = getAllKafkaRecords().firstOrNull { it.messageId == messageId }
		if (event != null)
			return event.payload.toString()

		throw NoSuchElementException("Could not find message with id $messageId")
	}


	private fun getAllEvents(itemFiler: (KafkaEventRaw<*>) -> Boolean): List<KafkaEvent> =
		createContentEventList(getAllKafkaRecords(), itemFiler)

	private fun createContentEventList(events: List<KafkaEventRaw<*>>,
																		 itemFiler: (KafkaEventRaw<*>) -> Boolean): List<KafkaEvent> {

		val sequence = generateSequence(0) { it + 1 }
			.take(events.size).toList()

		return events
			.filter { itemFiler.invoke(it) }
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


	private fun getAllKafkaRecords() = kafkaAdminConsumer.getAllKafkaRecords()
}
