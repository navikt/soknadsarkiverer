package no.nav.soknad.arkivering.soknadsarkiverer.admin

import no.nav.soknad.arkivering.soknadsarkiverer.admin.EventCollection.TimeSelector

/**
 * This class acts as a collection of [KafkaEvent]s, and with the method [addEvents(List)][addEvents], new elements
 * can be added. The collection can have a fixed [capacity], which it will not exceed, or it can be unbounded
 * (when `capacity <= 0`).
 *
 * Two types of filters can be applied:
 *
 * 1. A time filter. By setting [timeSelector] to [TimeSelector.BEFORE] or [TimeSelector.AFTER], all events in the
 * collection will be before respectively after the given [timestamp], and the rest are discarded. Any events
 * occurring ON the timestamp are also included. By setting [timeSelector] to [TimeSelector.ANY], no events are
 * discarded based on time, and [timestamp] will not be taken into account.
 *
 * 2. A custom filter. By setting [filter], one can discard events using custom logic. Set `filter = { true }` to
 * include all events.
 */
internal class EventCollection<T> private constructor(
	private val capacity: Int,
	private val timeSelector: TimeSelector,
	private val timestamp: Long,
	private val filter: (KafkaEvent<*>) -> Boolean
) {

	private var events: MutableList<KafkaEvent<T>> = mutableListOf()

	/**
	 * @return The collection of [KafkaEvent]s, sorted by timestamp.
	 */
	fun getEvents() = events
		.mapIndexed { index, event ->
			KafkaEvent(
				index,
				event.innsendingKey,
				event.messageId,
				event.timestamp,
				event.type,
				event.content
			)
		}

	/**
	 * @return The type of [TimeSelector] for this EventCollection.
	 */
	fun getTimeSelector() = timeSelector

	/**
	 * This function will take the [KafkaEvent]s in [list] that fulfill the class's filters, and add those
	 * to the internal collection of the class.
	 *
	 * @param [list] List of [KafkaEvent]s to add to the class instance's internal collection.
	 * @return A boolean signalling whether the [EventCollection] is now satisfied. If it is satisfied, there is no more
	 * need to feed the collection with new elements. This is given by the following logic:
	 * 1. If the collection of [events] had items previously, and [list] is empty, it returns true.
	 * 2. If [timeSelector] is [TimeSelector.ANY], and [list] is not empty, it returns false ([TimeSelector.ANY] means
	 * it should continue to consume for as long as there are new elements to feed it with).
	 * 2. Otherwise it returns true if the number of elements in the collection is now [capacity], and false otherwise.
	 */
	fun addEvents(list: List<KafkaEvent<T>>): Boolean {
		events.addAll(filterIncomingEvents(list))

		events = when {
			capacity <= 0 -> events.sortedByDescending { it.timestamp }.toMutableList()
			timeSelector == TimeSelector.AFTER -> events.sortedByDescending { it.timestamp }.takeLast(capacity).toMutableList()
			else -> events.sortedByDescending { it.timestamp }.take(capacity).toMutableList()
		}

		return isSatisfied(list)
	}

	private fun filterIncomingEvents(list: List<KafkaEvent<T>>): List<KafkaEvent<T>> {
		val timeFilteredList = when (timeSelector) {
			TimeSelector.BEFORE -> list.filter { it.timestamp <= timestamp }
			TimeSelector.AFTER  -> list.filter { it.timestamp >= timestamp }
			TimeSelector.ANY    -> list
		}
		return timeFilteredList.filter { filter.invoke(it) }
	}


	private fun isSatisfied(list: List<KafkaEvent<T>>): Boolean {
		return when {
			eventsHaveBeenPreviousAddedButThisOneWasEmpty(list) -> true
			timeSelector == TimeSelector.ANY -> false // We found new events in this call, but TimeSelector ANY means there can always be more relevant events
			else -> events.size == capacity
		}
	}

	private fun eventsHaveBeenPreviousAddedButThisOneWasEmpty(list: List<Any>) = events.isNotEmpty() && list.isEmpty()


	internal data class Builder(
		private var capacity: Int = 0,
		private var timeSelector: TimeSelector = TimeSelector.ANY,
		private var timestamp: Long = 0,
		private var filter: (KafkaEvent<*>) -> Boolean = { true },
	) {

		fun withoutCapacity() = apply { this.capacity = -1 }
		fun withCapacity(capacity: Int) = apply { this.capacity = capacity }
		fun withEventsBefore(timestamp: Long) = apply { this.timestamp = timestamp; this.timeSelector = TimeSelector.BEFORE }
		fun withEventsAfter(timestamp: Long) = apply { this.timestamp = timestamp; this.timeSelector = TimeSelector.AFTER }
		fun withMostRecentEvents() = apply { this.timeSelector = TimeSelector.ANY }
		fun withFilter(filter: (KafkaEvent<*>) -> Boolean) = apply { this.filter = filter }

		fun <T> build() = EventCollection<T>(capacity, timeSelector, timestamp, filter)
	}

	internal enum class TimeSelector { BEFORE, AFTER, ANY }
}
