package no.nav.soknad.arkivering.soknadsarkiverer.admin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.Month
import java.util.*

class EventCollectionTests {

	private val startTime = LocalDateTime.of(2020, Month.NOVEMBER, 10, 11, 37, 17, 2812)
	private val events: List<KafkaEventRaw<Any>> = listOf(
		KafkaEventRaw(uuid(), uuid(), startTime.minusSeconds(2), "One"),
		KafkaEventRaw(uuid(), uuid(), startTime.minusSeconds(1), "Two"),
		KafkaEventRaw(uuid(), uuid(), startTime, "Three"),
		KafkaEventRaw(uuid(), uuid(), startTime.plusSeconds(1), "Four"),
		KafkaEventRaw(uuid(), uuid(), startTime.plusSeconds(2), "Five"),
	)

	@Test
	fun `Get 3 latest events`() {

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withMostRecentEvents()
			.build()

		eventCollection.addEvents(events)

		assertTrue(eventCollection.isFull())
		assertEquals(listOf(events[4], events[3], events[2]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 events before timestamp`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsBefore(startTime.minusSeconds(1))
			.build()

		eventCollection.addEvents(events)

		assertFalse(eventCollection.isFull())
		assertEquals(listOf(events[1], events[0]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 events after timestamp`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsAfter(startTime.minusSeconds(1))
			.build()

		eventCollection.addEvents(events)

		assertTrue(eventCollection.isFull())
		assertEquals(listOf(events[3], events[2], events[1]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 latest filtered events`() {
		val filter = { kafkaEvent: KafkaEventRaw<Any> -> kafkaEvent.payload.toString().contains("o", true) }

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withMostRecentEvents()
			.withFilter(filter)
			.build()

		eventCollection.addEvents(events)

		assertTrue(eventCollection.isFull())
		assertEquals(listOf(events[3], events[1], events[0]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 filtered events before timestamp`() {
		val filter = { kafkaEvent: KafkaEventRaw<Any> -> kafkaEvent.payload.toString().contains("o", true) }

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsBefore(startTime.minusSeconds(1))
			.withFilter(filter)
			.build()

		eventCollection.addEvents(events)

		assertFalse(eventCollection.isFull())
		assertEquals(listOf(events[1], events[0]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 filtered events after timestamp`() {
		val filter = { kafkaEvent: KafkaEventRaw<Any> -> kafkaEvent.payload.toString().contains("o", true) }

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsAfter(startTime.plusSeconds(1))
			.withFilter(filter)
			.build()

		eventCollection.addEvents(events)

		assertFalse(eventCollection.isFull())
		assertEquals(listOf(events[3]), eventCollection.getEvents())
	}

	//TODO: Repeated calls to addEvents()

	private fun uuid() = UUID.randomUUID().toString()
}
