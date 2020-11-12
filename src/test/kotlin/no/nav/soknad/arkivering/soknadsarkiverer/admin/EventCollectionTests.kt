package no.nav.soknad.arkivering.soknadsarkiverer.admin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.Month
import java.util.*

class EventCollectionTests {

	private val baseTime = LocalDateTime.of(2020, Month.NOVEMBER, 10, 11, 37, 17, 2812)

	private val stringEvents: List<KafkaEventRaw<Any>> = listOf(
		KafkaEventRaw(uuid(), uuid(), baseTime.minusSeconds(2), "One"),
		KafkaEventRaw(uuid(), uuid(), baseTime.minusSeconds(1), "Two"),
		KafkaEventRaw(uuid(), uuid(), baseTime, "Three"),
		KafkaEventRaw(uuid(), uuid(), baseTime.plusSeconds(1), "Four"),
		KafkaEventRaw(uuid(), uuid(), baseTime.plusSeconds(2), "Five"),
	)
	private val intEvents: List<KafkaEventRaw<Any>> = listOf(
		KafkaEventRaw(uuid(), uuid(), baseTime.minusSeconds(3), 63),
		KafkaEventRaw(uuid(), uuid(), baseTime.plusNanos(1500000000 /* 1.5 seconds*/ ), 68),
		KafkaEventRaw(uuid(), uuid(), baseTime.plusSeconds(3), 71)
	)


	@Test
	fun `Get 3 latest events`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withMostRecentEvents()
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(stringEvents)
		val isSatisfied1 = eventCollection.addEvents(emptyList())

		assertFalse(isSatisfied0, "Most recent events requested - should not be satisfied")
		assertTrue(isSatisfied1, "No more events added - should be satisfied now")
		assertEquals(listOf(stringEvents[4], stringEvents[3], stringEvents[2]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 events before timestamp`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsBefore(baseTime.minusSeconds(1))
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(stringEvents)
		val isSatisfied1 = eventCollection.addEvents(emptyList())

		assertFalse(isSatisfied0, "EventCollection has not reached its capacity - should not be satisfied")
		assertTrue(isSatisfied1, "No more events added - should be satisfied now")
		assertEquals(listOf(stringEvents[1], stringEvents[0]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 events after timestamp`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsAfter(baseTime.minusSeconds(1))
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(stringEvents)
		val isSatisfied1 = eventCollection.addEvents(emptyList())

		assertTrue(isSatisfied0, "EventCollection has reached its capacity - should be satisfied")
		assertTrue(isSatisfied1, "No more events added - should still be satisfied")
		assertEquals(listOf(stringEvents[3], stringEvents[2], stringEvents[1]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 latest filtered events`() {
		val filter = { kafkaEvent: KafkaEventRaw<*> -> kafkaEvent.payload.toString().contains("o", true) }

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withMostRecentEvents()
			.withFilter(filter)
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(stringEvents)
		val isSatisfied1 = eventCollection.addEvents(emptyList())

		assertFalse(isSatisfied0, "Most recent events requested - should not be satisfied")
		assertTrue(isSatisfied1, "No more events added - should be satisfied now")
		assertEquals(listOf(stringEvents[3], stringEvents[1], stringEvents[0]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 filtered events before timestamp`() {
		val filter = { kafkaEvent: KafkaEventRaw<*> -> kafkaEvent.payload.toString().contains("o", true) }

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsBefore(baseTime.minusSeconds(1))
			.withFilter(filter)
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(stringEvents)
		val isSatisfied1 = eventCollection.addEvents(emptyList())

		assertFalse(isSatisfied0, "EventCollection has not reached its capacity - should not be satisfied")
		assertTrue(isSatisfied1, "No more events added - should be satisfied now")
		assertEquals(listOf(stringEvents[1], stringEvents[0]), eventCollection.getEvents())
	}

	@Test
	fun `Get 3 filtered events after timestamp`() {
		val filter = { kafkaEvent: KafkaEventRaw<*> -> kafkaEvent.payload.toString().contains("o", true) }

		val eventCollection = EventCollection.Builder()
			.withCapacity(3)
			.withEventsAfter(baseTime.plusSeconds(1))
			.withFilter(filter)
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(stringEvents)
		val isSatisfied1 = eventCollection.addEvents(emptyList())

		assertFalse(isSatisfied0, "EventCollection has not reached its capacity - should not be satisfied")
		assertTrue(isSatisfied1, "No more events added - should be satisfied now")
		assertEquals(listOf(stringEvents[3]), eventCollection.getEvents())
	}


	@Test
	fun `Several adds, get 4 latest events`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(4)
			.withMostRecentEvents()
			.build<Any>()


		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, and Most recent events requested - should not be satisfied")
		assertFalse(isSatisfied2, "Events added, and Most recent events requested - should not be satisfied")
		assertTrue(isSatisfied3, "No more events added - should be satisfied now")
		assertEquals(listOf(intEvents[2], stringEvents[4], intEvents[1], stringEvents[3]), eventCollection.getEvents())
	}

	@Test
	fun `Several adds, get 4 events before timestamp`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(4)
			.withEventsBefore(baseTime)
			.build<Any>()


		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, but not enough Events Before to fill capacity - should not be satisfied")
		assertTrue(isSatisfied2, "Events added, now capacity is full - should be satisfied now")
		assertTrue(isSatisfied3, "Was already satisfied - should still be")
		assertEquals(listOf(stringEvents[2], stringEvents[1], stringEvents[0], intEvents[0]), eventCollection.getEvents())
	}

	@Test
	fun `Several adds, get 4 events after timestamp`() {
		val eventCollection = EventCollection.Builder()
			.withCapacity(4)
			.withEventsAfter(baseTime)
			.build<Any>()


		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, but not enough Events After to fill capacity - should not be satisfied")
		assertTrue(isSatisfied2, "Events added, now capacity is full - should be satisfied now")
		assertTrue(isSatisfied3, "Was already satisfied - should still be")
		assertEquals(listOf(stringEvents[4], intEvents[1], stringEvents[3], stringEvents[2]), eventCollection.getEvents())
	}

	@Test
	fun `Several adds, get 4 latest filtered events`() {
		val payloadIsOddNumberOrContainsTheLetterO = { kafkaEvent: KafkaEventRaw<*> ->
			when (kafkaEvent.payload) {
				is Int -> kafkaEvent.payload as Int % 2 != 0
				else -> kafkaEvent.payload.toString().contains("o", true)
			}
		}

		val eventCollection = EventCollection.Builder()
			.withCapacity(4)
			.withMostRecentEvents()
			.withFilter(payloadIsOddNumberOrContainsTheLetterO)
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, and Most recent events requested - should not be satisfied")
		assertFalse(isSatisfied2, "Events added, and Most recent events requested - should not be satisfied")
		assertTrue(isSatisfied3, "No more events added - should be satisfied now")
		// Filter removes "Three", "Five", 68
		// Left are: 63, "One", "Two", "Four", 71
		assertEquals(listOf(intEvents[2], stringEvents[3], stringEvents[1], stringEvents[0]), eventCollection.getEvents())
	}

	@Test
	fun `Several adds, get 4 filtered events before timestamp`() {
		val payloadIsOddNumberOrContainsTheLetterO = { kafkaEvent: KafkaEventRaw<*> ->
			when (kafkaEvent.payload) {
				is Int -> kafkaEvent.payload as Int % 2 != 0
				else -> kafkaEvent.payload.toString().contains("o", true)
			}
		}

		val eventCollection = EventCollection.Builder()
			.withCapacity(4)
			.withEventsBefore(baseTime)
			.withFilter(payloadIsOddNumberOrContainsTheLetterO)
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, and capacity is not full - should not be satisfied")
		assertFalse(isSatisfied2, "Events added, and capacity is not full - should not be satisfied")
		assertTrue(isSatisfied3, "No more events added - should be satisfied now")
		// Time limit removes "Four", "Five", 68, 71
		// Filter removes "Three", "Five", 68
		// Left are: 63, "One", "Two"
		assertEquals(listOf(stringEvents[1], stringEvents[0], intEvents[0]), eventCollection.getEvents())
	}

	@Test
	fun `Several adds, get 4 filtered events after timestamp`() {
		val payloadIsOddNumberOrContainsTheLetterO = { kafkaEvent: KafkaEventRaw<*> ->
			when (kafkaEvent.payload) {
				is Int -> kafkaEvent.payload as Int % 2 != 0
				else -> kafkaEvent.payload.toString().contains("o", true)
			}
		}

		val eventCollection = EventCollection.Builder()
			.withCapacity(4)
			.withEventsAfter(baseTime)
			.withFilter(payloadIsOddNumberOrContainsTheLetterO)
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, and capacity is not full - should not be satisfied")
		assertFalse(isSatisfied2, "Events added, and capacity is not full - should not be satisfied")
		assertTrue(isSatisfied3, "No more events added - should be satisfied now")
		// Time limit removes "One", "Two", 63
		// Filter removes "Three", "Five", 68
		// Left are: "Four", 71
		assertEquals(listOf(intEvents[2], stringEvents[3]), eventCollection.getEvents())
	}

	@Test
	fun `Several adds, no capacity`() {
		val eventCollection = EventCollection.Builder()
			.withoutCapacity()
			.withMostRecentEvents()
			.build<Any>()

		val isSatisfied0 = eventCollection.addEvents(emptyList())
		val isSatisfied1 = eventCollection.addEvents(stringEvents)
		val isSatisfied2 = eventCollection.addEvents(intEvents)
		val isSatisfied3 = eventCollection.addEvents(emptyList())


		assertFalse(isSatisfied0, "Initial adding of empty list - should not be satisfied")
		assertFalse(isSatisfied1, "Events added, and capacity is not full - should not be satisfied")
		assertFalse(isSatisfied2, "Events added, and capacity is not full - should not be satisfied")
		assertTrue(isSatisfied3, "No more events added - should be satisfied now")
		assertEquals(listOf(intEvents[2], stringEvents[4], intEvents[1], stringEvents[3], stringEvents[2], stringEvents[1], stringEvents[0], intEvents[0]), eventCollection.getEvents())
	}


	private fun uuid() = UUID.randomUUID().toString()
}
