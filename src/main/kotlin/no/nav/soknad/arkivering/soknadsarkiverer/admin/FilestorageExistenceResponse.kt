package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotBlank

data class FilestorageExistenceResponse(
	@Schema(description = "The key of a file in Filestorage",
		example = "22d47115-215e-4e0f-a5c8-3dc38d837159", required = true)
	@NotBlank
	val id: String,

	@Schema(description = "Whether the file exists or not.",
		example = "DOES_NOT_EXIST", required = true)
	@NotBlank
	val status: FilestorageExistenceStatus)

enum class FilestorageExistenceStatus { EXISTS, DOES_NOT_EXIST, FAILED_TO_FIND_FILE_IDS }
