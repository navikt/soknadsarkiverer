package no.nav.soknad.arkivering.soknadsarkiverer.dto

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent

/**
 * Used to group ProcessingEvents with the same key together.
 */
class ProcessingEventDto(processingEventStrings: List<String>) {
	private val values = mutableListOf<ProcessingEvent>()

	init {
		processingEventStrings.forEach {
			values.add(ProcessingEvent(EventTypes.valueOf(it)))
		}
	}

	fun isFinished() = values.contains(ProcessingEvent(EventTypes.FINISHED))

	fun getNumberOfStarts(): Int {
		return values.filter { it == ProcessingEvent(EventTypes.STARTED) }.count()
	}
}
