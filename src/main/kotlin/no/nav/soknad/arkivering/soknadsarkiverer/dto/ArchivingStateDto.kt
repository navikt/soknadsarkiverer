package no.nav.soknad.arkivering.soknadsarkiverer.dto

import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema

class ArchivingStateDto(state: ProcessingEvent, soknadarkivschema: Soknadarkivschema) {
	private val state = state
	private val soknadarkivschema = soknadarkivschema

	fun getState():ProcessingEvent = state

	fun getSoknadarkivschema(): Soknadarkivschema = soknadarkivschema
}
