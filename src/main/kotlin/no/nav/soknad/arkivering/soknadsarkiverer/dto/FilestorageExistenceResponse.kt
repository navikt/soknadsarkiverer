package no.nav.soknad.arkivering.soknadsarkiverer.dto

import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotBlank

data class FilestorageExistenceResponse(
	@Schema(description = "The key of a file in Filestorage",
		example = "22d47115-215e-4e0f-a5c8-3dc38d837159", required = true)
	@NotBlank
	val id: String,

	@Schema(description = "Whether the file exists or not.", //TODO: Introduce enum?
		example = "Does not exist", required = true)
	@NotBlank
	val status: String)
