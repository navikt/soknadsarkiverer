package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.ArgumentMatchers
import java.util.concurrent.TimeUnit

fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
									finalCheck: () -> Any = { assertEquals(expectedCount, getCount.invoke()) }) {
	loopAndVerify(getCount, expectedCount, finalCheck, { a, b -> a == b } )
}

fun loopAndVerifyAtLeast(expectedCount: Int, getCount: () -> Int,
												 finalCheck: () -> Any = {
													 val actual = getCount.invoke()
													 assertTrue(expectedCount <= actual, "Expected $expectedCount, was $actual")
												 }) {
	loopAndVerify(getCount, expectedCount, finalCheck, { a, b -> a <= b } )
}

private fun loopAndVerify(getCount: () -> Int, expectedCount: Int, finalCheck: () -> Any, compareMethod: (Int, Int) -> Boolean) {
	val startTime = System.currentTimeMillis()
	val timeout = 60 * 1000

	while (System.currentTimeMillis() < startTime + timeout) {
		val matches = getCount.invoke()

		if (compareMethod.invoke(expectedCount, matches))
			break
		TimeUnit.MILLISECONDS.sleep(50)
	}
	finalCheck.invoke()
}

fun verifyProcessingEventsSupport(kafkaPublisherMock: KafkaPublisher, expectedCount: Int, eventType: EventTypes, key: String) {
	val type = ProcessingEvent(eventType)
	val getCount = {
		mockingDetails(kafkaPublisherMock)
			.invocations.stream()
			.filter { it.arguments[0] == key }
			.filter { it.arguments[1] == type }
			.count()
			.toInt()
	}

	val finalCheck = { verify(kafkaPublisherMock, atLeast(expectedCount)).putProcessingEventOnTopic(eq(key), eq(type), any()) }
	loopAndVerify(expectedCount, getCount, finalCheck)
}

fun verifyMetricSupport(kafkaPublisherMock: KafkaPublisher, expectedCount: Int, metric: String, key: String ) {
	val getCount = {
		mockingDetails(kafkaPublisherMock)
			.invocations.stream()
			.filter { it.arguments[0] == key }
			.filter { it.arguments[1] is InnsendingMetrics }
			.filter { (it.arguments[1] as InnsendingMetrics).toString().contains(metric) }
			.count()
			.toInt()
	}

	loopAndVerify(expectedCount, getCount)
}

fun verifyMessageStartsWithSupport(kafkaPublisherMock: KafkaPublisher, expectedCount: Int, message: String, key: String) {
	val getCount = {
		mockingDetails(kafkaPublisherMock)
			.invocations.stream()
			.filter { it.arguments[0] == key }
			.filter { it.arguments[1] is String }
			.filter { (it.arguments[1] as String).startsWith(message) }
			.count()
			.toInt()
	}

	val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putMessageOnTopic(eq(key),
		ArgumentMatchers.startsWith(message), any()) }
	loopAndVerify(expectedCount, getCount, finalCheck)
}

