package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.soknad.arkivering.api.AdminApi
import no.nav.soknad.arkivering.model.ArchivingStatus
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafService
import no.nav.soknad.arkivering.soknadsarkiverer.service.safservice.SafServiceInterface
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@RestController
@ProtectedWithClaims(issuer = "azuread")
@RequestMapping("/admin")
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

	private fun konverterTilDateTime(dateString: String): OffsetDateTime {
		if (dateString.isBlank()) return OffsetDateTime.MIN
		val formatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME
		val dateTime = LocalDateTime.parse(dateString, formatter)
		val zoneOffSet = OffsetDateTime.now().offset
		return dateTime.atOffset(zoneOffSet)
	}

}
