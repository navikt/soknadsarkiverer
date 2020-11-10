package no.nav.soknad.arkivering.soknadsarkiverer.admin

import java.time.LocalDateTime

internal class EventCollection private constructor(
	private val numberOfEvents: Int,
	private val timeSelector: TimeSelector,
	private val timestamp: LocalDateTime,
	private val filter: (KafkaEventRaw<Any>) -> Boolean
) {

	private var events: MutableList<KafkaEventRaw<Any>> = mutableListOf()

	fun addEvents(list: List<KafkaEventRaw<Any>>) {
		val timeFilteredList = when (timeSelector) {
			TimeSelector.BEFORE -> list.filter { it.timestamp.isBefore(timestamp) || it.timestamp.isEqual(timestamp) }
			TimeSelector.AFTER -> list.filter { it.timestamp.isAfter(timestamp) || it.timestamp.isEqual(timestamp) }
			TimeSelector.ANY -> list
		}
		val filteredList = timeFilteredList.filter { filter.invoke(it) }
		events.addAll(filteredList)

		events = if (timeSelector == TimeSelector.AFTER)
			events.sortedByDescending { it.timestamp }.takeLast(numberOfEvents).toMutableList()
		else
			events.sortedByDescending { it.timestamp }.take(numberOfEvents).toMutableList()
	}

	fun isFull() = events.size == numberOfEvents

	fun getEvents() = events.toList()


	internal data class Builder(
		var numberOfEvents: Int = 0,
		var timeSelector: TimeSelector = TimeSelector.ANY,
		var timestamp: LocalDateTime = LocalDateTime.MIN,
		var filter: (KafkaEventRaw<Any>) -> Boolean = { true }
	) {

		fun withCapacity(capacity: Int) = apply { this.numberOfEvents = capacity }
		fun withEventsBefore(timestamp: LocalDateTime) = apply { this.timestamp = timestamp; this.timeSelector = TimeSelector.BEFORE }
		fun withEventsAfter(timestamp: LocalDateTime) = apply { this.timestamp = timestamp; this.timeSelector = TimeSelector.AFTER }
		fun withMostRecentEvents() = apply { this.timeSelector = TimeSelector.ANY }
		fun withFilter(filter: (KafkaEventRaw<Any>) -> Boolean) = apply { this.filter = filter }

		fun build() = EventCollection(numberOfEvents, timeSelector, timestamp, filter)
	}

	internal enum class TimeSelector { BEFORE, AFTER, ANY }
}
