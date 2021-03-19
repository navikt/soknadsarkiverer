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

	fun isFailed() = values.contains(ProcessingEvent(EventTypes.FAILURE))

	fun isFinished() = values.contains(ProcessingEvent(EventTypes.FINISHED))

	fun getNumberOfStarts(): Int {
		return values.filter { it == ProcessingEvent(EventTypes.STARTED) }.count()
	}

	fun getNewestState(): ProcessingEvent {
		if (values.contains(ProcessingEvent(EventTypes.FINISHED))) return ProcessingEvent(EventTypes.FINISHED)

		if (values.last().type == EventTypes.FAILURE) return ProcessingEvent(EventTypes.FAILURE)

		if (values.contains(ProcessingEvent(EventTypes.ARCHIVED))) return ProcessingEvent(EventTypes.ARCHIVED)

		if (values.contains(ProcessingEvent(EventTypes.STARTED))) return ProcessingEvent(EventTypes.STARTED)

		if (values.contains(ProcessingEvent(EventTypes.RECEIVED))) return ProcessingEvent(EventTypes.RECEIVED)

		if (values.contains(ProcessingEvent(EventTypes.FAILURE))) return ProcessingEvent(EventTypes.FAILURE)

		return values.last()
	}
}
