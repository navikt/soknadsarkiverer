package no.nav.soknad.arkivering.soknadsarkiverer.utils

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.TimeUnit

fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
									finalCheck: () -> Any = { assertEquals(expectedCount, getCount.invoke()) }) {
	val startTime = System.currentTimeMillis()
	val timeout = 30 * 1000

	while (System.currentTimeMillis() < startTime + timeout) {
		val matches = getCount.invoke()

		if (matches == expectedCount) {
			break
		}
		TimeUnit.MILLISECONDS.sleep(50)
	}
	finalCheck.invoke()
}

fun verifyProcessingEvents(kafkaPublisherMock: KafkaPublisher, key: String, eventType: EventTypes, expectedCount: Int) {
	val type = ProcessingEvent(eventType)
	val getCount = {
		mockingDetails(kafkaPublisherMock)
			.invocations.stream()
			.filter { it.arguments[0] == key }
			.filter { it.arguments[1] == type }
			.count()
			.toInt()
	}

	val finalCheck = { verify(kafkaPublisherMock, times(expectedCount)).putProcessingEventOnTopic(eq(key), eq(type), any()) }
	loopAndVerify(expectedCount, getCount, finalCheck)
}
