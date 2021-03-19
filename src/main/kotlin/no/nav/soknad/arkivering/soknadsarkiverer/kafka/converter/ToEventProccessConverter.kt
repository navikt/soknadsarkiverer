package no.nav.soknad.arkivering.soknadsarkiverer.kafka.converter

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent

fun createProcessEvent(type: EventTypes): ProcessingEvent {
	return ProcessingEvent(type)
}


