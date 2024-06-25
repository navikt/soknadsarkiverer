package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Parameter
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.soknad.arkivering.api.AdminApi
import no.nav.soknad.arkivering.model.ArchivingStatus
import no.nav.soknad.arkivering.model.Document
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import no.nav.soknad.arkivering.soknadsarkiverer.util.konverterTilDateTime
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@ProtectedWithClaims(issuer = "azuread")
class ApplicationAdminInterface(private val taskListService: TaskListService, private val safService: SafServiceInterface): AdminApi {
	private val logger = LoggerFactory.getLogger(javaClass)

	override fun rerun(@Parameter(description = "Key of a Soknadsarkivschema") @PathVariable key: String): ResponseEntity<Unit> {
		logger.info("$key: Performing forced rerun")

		taskListService.startPaNytt(key)

		return ResponseEntity
			.status(HttpStatus.OK)
			.body(Unit)
	}

	override fun isArchived(@Parameter(description = "application key") @PathVariable key: String): ResponseEntity<ArchivingStatus> {
		logger.info("$key: Check if application is archived")

		val journalpost = safService.hentJournalpostGittInnsendingId(key)

		if (journalpost == null) {
			return ResponseEntity
				.status(HttpStatus.NOT_FOUND)
				.body(ArchivingStatus(key, null, null))
		} else {
			return ResponseEntity
				.status(HttpStatus.OK)
				.body(ArchivingStatus(key, journalpost.journalpostId, konverterTilDateTime(journalpost.datoOpprettet)))
		}

	}

	override fun hentDokumenter(key: String): ResponseEntity<List<Document>> {
		logger.info("$key: Admin - hentdokumenter")
		val documents =  taskListService.applicationsAttachments(key)
		if (documents.isEmpty() ) {
			logger.info("$key: Søknaden ikke funnet i denne PODen")
			return ResponseEntity
				.status(HttpStatus.NOT_FOUND)
				.body(emptyList<Document>())
		} else {
			return ResponseEntity
				.status(HttpStatus.OK)
				.body(documents)
		}
	}

	override fun arkiveringfeilet(): ResponseEntity<List<String>> {
		val failedApplications = taskListService.getFailedTasks()
		return ResponseEntity
			.status(HttpStatus.OK)
			.body(failedApplications)
	}

}
