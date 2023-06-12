package no.nav.soknad.arkivering.soknadsarkiverer.service

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.config.ShuttingDownException
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileserviceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.InnsendingService
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

	fun archive(key: String, data: Soknadarkivschema, files: List<FileData>) {
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

	 fun fetchFiles(key: String, data: Soknadarkivschema): List<FileData> {
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

	fun makeParallelCallsToFetchFiles(key: String, data: Soknadarkivschema): List<FileData> {
		return runBlocking {
			val fileStorageResult = async { filestorageService.getFilesFromFilestorage(key, data) }
			val innsendingApiResult = async { innsendingService.getFilesFromFilestorage(key, data) }

			val responses = listOf(fileStorageResult, innsendingApiResult).awaitAll()
			val okResponse = responses.filter { it.status == "ok" }.firstOrNull()
			if (okResponse != null) {
				metrics.incGetFilestorageSuccesses()
				if (okResponse.files != null) okResponse.files else listOf<FileData>()
			} else {
					val deletedResponse = responses.filter { it.status == "deleted" }.firstOrNull()
					if (deletedResponse != null) {
						throw FilesAlreadyDeletedException("$key: All the files are deleted.")
					} else {
						metrics.incGetFilestorageErrors()
						val notFoundResponse = responses.filter { it.status == "not-found" }.firstOrNull()
						if (notFoundResponse != null && notFoundResponse.files != null && notFoundResponse.files.any{it.status == "ok"})
						throw ArchivingException(
							"$key: Files had different statuses: ${notFoundResponse.files.map { "${it.id} - ${it.status}" }}",
							RuntimeException("$key: Got some, but not all files")
						)
						val exceptionResponse = responses.filter { it.status == "error" }.firstOrNull()
						throw ArchivingException(
							"$key: Files not found",
							if (exceptionResponse != null && exceptionResponse.exception != null) exceptionResponse.exception
							else RuntimeException("$key: Files not found")
						)
					}
				}
		}
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


	public fun createMessage(key: String, message: String) {
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
