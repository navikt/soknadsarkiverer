package no.nav.soknad.arkivering.soknadsarkiverer.service

import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.ArchivingException
import no.nav.soknad.arkivering.soknadsarkiverer.config.ShuttingDownException
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.service.arkivservice.JournalpostClientInterface
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FileInfo
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.FilesAlreadyDeletedException
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.InnsendingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice.ResponseStatus
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter

@Service
class ArchiverService(
	private val innsendingService: InnsendingService,
	private val journalpostClient: JournalpostClientInterface,
	private val metrics: ArchivingMetrics,
	private val kafkaPublisher: KafkaPublisher
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun archive(key: String, data: Soknadarkivschema, files: List<FileInfo>) {
		try {
			val startTime = System.currentTimeMillis()
			val journalpostId = journalpostClient.opprettJournalpost(key, data, files)
			createMetricAndPublishOnKafka(key, "send files to archive", startTime)
			logger.info("$key: Opprettet journalpostId=$journalpostId for behandlingsid=${data.behandlingsid}")
			// TODO fjern createMessage når innsending-api leser fra arkiveringstilbakemeldinger topic
			createMessage(key, "**Archiving: OK.  journalpostId=$journalpostId")
			createArkiveringstilbakemelding(key, "**Archiving: OK.  journalpostId=$journalpostId")

		} catch (e: ApplicationAlreadyArchivedException) {
			// TODO fjern createMessage når innsending-api leser fra arkiveringstilbakemeldinger topic
			createMessage(key, "**Archiving: OK. Already archived")
			createArkiveringstilbakemelding(key, "**Archiving: OK. Already archived")
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
			val response = innsendingService.getFilesFromFilestorage(key, data)
			val files = when (response.status) {
				ResponseStatus.Ok.value -> (response.files ?: emptyList()).also { metrics.incGetFilestorageSuccesses() }
				ResponseStatus.Deleted.value -> throw FilesAlreadyDeletedException("$key: All the files are deleted.")
				else -> {
					throw ArchivingException(
						"$key: Files not found",
						response.exception ?: RuntimeException("$key: Files not found")
					).also { metrics.incGetFilestorageErrors() }
				}
			}

			createMetricAndPublishOnKafka(key, "get files from filestorage", startTime)
			files.filter { it.content != null }.forEach {
				metrics.setFileFetchSizeHistogram(it.content!!.size.toDouble(), data.arkivtema)
				metrics.setFileFetchSize(it.content.size.toDouble())
			}

			files

		} catch (e: ShuttingDownException) {
			logger.warn("$key: Will not start to fetchFiles - application is shutting down.")
			emptyList()

		} catch (e: Exception) {
			createMessage(key, createExceptionMessage(e))
			throw e
		}
	}

	fun createMessage(key: String, message: String) {
		logger.info("$key: publiser meldingsvarsling til avsender")
		kafkaPublisher.putMessageOnTopic(key, message)
	}

	fun createArkiveringstilbakemelding(key: String, message: String) {
		logger.info("$key: publiser arkiveringstilbakemelding til avsender")
		kafkaPublisher.putArkiveringstilbakemeldingOnTopic(key, message)
	}

	private fun createMetricAndPublishOnKafka(key: String, message: String, startTime: Long) {
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
