package no.nav.soknad.arkivering.soknadsarkiverer.admin

import java.time.LocalDateTime

// TODO: Document
internal class EventCollection<T> private constructor(
	private val numberOfEvents: Int,
	private val timeSelector: TimeSelector,
	private val timestamp: LocalDateTime,
	private val filter: (KafkaEventRaw<*>) -> Boolean
) {

	private var events: MutableList<KafkaEventRaw<T>> = mutableListOf()

	fun addEvents(list: List<KafkaEventRaw<T>>): Boolean {
		val timeFilteredList = when (timeSelector) {
			TimeSelector.BEFORE -> list.filter { it.timestamp.isBefore(timestamp) || it.timestamp.isEqual(timestamp) }
			TimeSelector.AFTER -> list.filter { it.timestamp.isAfter(timestamp) || it.timestamp.isEqual(timestamp) }
			TimeSelector.ANY -> list
		}
		val filteredList = timeFilteredList.filter { filter.invoke(it) }
		events.addAll(filteredList)

		events = when {
				numberOfEvents <= 0 -> events.sortedByDescending { it.timestamp }.toMutableList()
				timeSelector == TimeSelector.AFTER -> events.sortedByDescending { it.timestamp }.takeLast(numberOfEvents).toMutableList()
				else -> events.sortedByDescending { it.timestamp }.take(numberOfEvents).toMutableList()
		}

		return isSatisfied(list)
	}

	fun getEvents() = events.toList()


	private fun isSatisfied(list: List<KafkaEventRaw<T>>): Boolean {
		return when {
				eventsHaveBeenPreviousAddedButThisOneWasEmpty(list) -> true
				timeSelector == TimeSelector.ANY -> false // Found new events in this call. TimeSelector ANY means there can always be more relevant events
				else -> events.size == numberOfEvents
		}
	}

	private fun eventsHaveBeenPreviousAddedButThisOneWasEmpty(list: List<Any>) = events.isNotEmpty() && list.isEmpty()


	internal data class Builder(
		private var numberOfEvents: Int = 0,
		private var timeSelector: TimeSelector = TimeSelector.ANY,
		private var timestamp: LocalDateTime = LocalDateTime.MIN,
		private var filter: (KafkaEventRaw<*>) -> Boolean = { true },
	) {

		fun withoutCapacity() = apply { this.numberOfEvents = -1 }
		fun withCapacity(capacity: Int) = apply { this.numberOfEvents = capacity }
		fun withEventsBefore(timestamp: LocalDateTime) = apply { this.timestamp = timestamp; this.timeSelector = TimeSelector.BEFORE }
		fun withEventsAfter(timestamp: LocalDateTime) = apply { this.timestamp = timestamp; this.timeSelector = TimeSelector.AFTER }
		fun withMostRecentEvents() = apply { this.timeSelector = TimeSelector.ANY }
		fun withFilter(filter: (KafkaEventRaw<*>) -> Boolean) = apply { this.filter = filter }

		fun <T> build() = EventCollection<T>(numberOfEvents, timeSelector, timestamp, filter)
	}

	internal enum class TimeSelector { BEFORE, AFTER, ANY }
}
