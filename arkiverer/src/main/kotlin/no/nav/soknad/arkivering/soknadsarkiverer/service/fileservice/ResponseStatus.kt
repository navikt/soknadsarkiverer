package no.nav.soknad.arkivering.soknadsarkiverer.service.fileservice

enum class ResponseStatus(val value: String) {
	Ok("ok"),
	NotFound("not-found"),
	Deleted("deleted"),
	Error("error")
}
