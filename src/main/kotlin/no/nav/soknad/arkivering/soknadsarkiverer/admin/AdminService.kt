package no.nav.soknad.arkivering.soknadsarkiverer.admin

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.soknadsarkiverer.admin.FilestorageExistenceStatus.*
import no.nav.soknad.arkivering.soknadsarkiverer.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

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
			return listOf(FilestorageExistenceResponse(key, FAILED_TO_FIND_FILE_IDS))
		}

		val response = fileService.getFilesFromFilestorage(key, soknadarkivschema)
		return response.map { FilestorageExistenceResponse(it.uuid, if (it.fil != null) EXISTS else DOES_NOT_EXIST) }
	}


	internal fun getUnfinishedEvents(builder: EventCollection.Builder): List<KafkaEvent<String>> {
		val finishedKeys = getAllFinishedKeys()
		builder.withFilter { event -> !finishedKeys.contains(event.innsendingKey) }

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
}
