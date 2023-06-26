package no.nav.soknad.arkivering.soknadsarkiverer.service

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.config.ShuttingDownException
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.*
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter

@Service
class ArchiverService(private val filestorageService: FileserviceInterface,
											private val innsendingService: InnsendingService,
											private val journalpostClient: JournalpostClientInterface,
											private val metrics: ArchivingMetrics,
											private val kafkaPublisher: KafkaPublisher) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(key: String, data: Soknadarkivschema, files: List<FileInfo>) {
		try {
			val startTime = System.currentTimeMillis()
			val journalpostId = journalpostClient.opprettJournalpost(key, data, files)
			createMetric(key, "send files to archive", startTime)
			logger.info("$key: Opprettet journalpostId=$journalpostId for behandlingsid=${data.behandlingsid}")
			createMessage(key, "**Archiving: OK.  journalpostId=$journalpostId")

		} catch (e: ApplicationAlreadyArchivedException) {
			createMessage(key, "**Archiving: OK. Already archived")
			createMessage(key, createExceptionMessage(e))
			throw e
		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	 suspend fun fetchFiles(key: String, data: Soknadarkivschema): List<FileInfo> {
		return try {
			val startTime = System.currentTimeMillis()
			val files = makeParallelCallsToFetchFiles(key, data)
			createMetric(key, "get files from filestorage", startTime)
			files

		} catch (e: ShuttingDownException) {
			logger.warn("$key: Will not start to fetchFiles - application is shutting down.")
			emptyList()

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	suspend fun makeParallelCallsToFetchFiles(key: String, data: Soknadarkivschema): List<FileInfo> {
		return withContext(Dispatchers.IO) {
			val innsendingApiResult = async { innsendingService.getFilesFromFilestorage(key, data) }
			val fileStorageResult = async { filestorageService.getFilesFromFilestorage(key, data) }

			val responses = listOf(fileStorageResult, innsendingApiResult).awaitAll()
			logger.info("$key: respons fra filkilder ${responses.map { it.status }.toList()}")

			selectAndReturnResponse(responses, key)
		}
	}

	fun selectAndReturnResponse (
		responses: List<FetchFileResponse>,
		key: String
	): List<FileInfo> {
		val okResponse = responses.firstOrNull { it.status == ResponseStatus.Ok.value }
		if (okResponse != null) {
			metrics.incGetFilestorageSuccesses()
			if (okResponse.files != null)
				return okResponse.files
			else
				return listOf()
		}

		val deletedResponse = responses.firstOrNull { it.status == "deleted" }
		if (deletedResponse != null) {
			throw FilesAlreadyDeletedException("$key: All the files are deleted.")
		}

		metrics.incGetFilestorageErrors()
		val notFoundResponse = responses.firstOrNull { it.status == "not-found" }
		if ((notFoundResponse?.files != null) && notFoundResponse.files.any { it.status == ResponseStatus.Ok })
			throw ArchivingException(
				"$key: Files had different statuses: ${notFoundResponse.files.map { "${it.uuid} - ${it.status}" }}",
				RuntimeException("$key: Got some, but not all files")
			)
		val exceptionResponse = responses.firstOrNull { it.status == "error" }
		throw ArchivingException(
			"$key: Files not found",
			if (exceptionResponse?.exception != null) exceptionResponse.exception
			else RuntimeException("$key: Files not found")
		)
	}

	fun deleteFiles(key: String, data: Soknadarkivschema) {
		try {
			val startTime = System.currentTimeMillis()
			filestorageService.deleteFilesFromFilestorage(key, data)
			createMetric(key, "delete files from filestorage", startTime)
			createMessage(key, "ok")

		} catch (e: ShuttingDownException) {
			logger.warn("$key: Will not start to deleteFiles - application is shutting down.")

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}


	fun createMessage(key: String, message: String) {
		logger.info("$key: publiser meldingsvarsling til avsender")
		kafkaPublisher.putMessageOnTopic(key, message)
	}

	private fun createMetric(key: String, message: String, startTime: Long) {
		val duration = System.currentTimeMillis() - startTime
		val metrics = InnsendingMetrics("soknadsarkiverer", message, startTime, duration)
		kafkaPublisher.putMetricOnTopic(key, metrics)
	}

	private fun createExceptionMessage(e: Exception): String {
		val sw = StringWriter()
		e.printStackTrace(PrintWriter(sw))
		val stacktrace = sw.toString()

		return "Exception when archiving: '" + e.message + "'\n" + stacktrace
	}
}
