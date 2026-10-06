package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.function.Supplier

class KafkaClientLifecycleTests {
	private val kafkaConfig = KafkaConfig(
		applicationId = "lifecycle-test",
		brokers = "localhost:9092",
		bootstrappingTimeout = "1",
		delayBeforeKafkaInitialization = "0",
		security = SecurityConfig("FALSE", "", "", "", "", "", ""),
		topics = Topics("processing", "messages", "feedback", "metrics", "nologin", "loggedin"),
		schemaRegistry = SchemaRegistry("mock://lifecycle-tests", "", "")
	)
	private val streams = mockk<KafkaStreams>(relaxed = true)
	private val setup = spyk(
		KafkaStreamsSetup(
			ApplicationState(),
			mockk<TaskListService>(relaxed = true),
			mockk<KafkaPublisher>(relaxed = true),
			kafkaConfig
		)
	).also {
		every { it.createKafkaStreams(any(), any()) } returns streams
	}

	@Test
	fun `Closing the Spring context stops all four Kafka publisher network threads`() {
		val existingThreads = Thread.getAllStackTraces().keys
		var producerThreads = emptySet<Thread>()

		AnnotationConfigApplicationContext().use { context ->
			context.registerBean(KafkaPublisher::class.java, Supplier { KafkaPublisher(kafkaConfig) })
			context.refresh()
			producerThreads = Thread.getAllStackTraces().keys.filter {
				it !in existingThreads && it.name.startsWith("kafka-producer-network-thread")
			}.toSet()

			assertEquals(4, producerThreads.size)
		}

		assertTrue(producerThreads.none { it.isAlive }, "Kafka publisher threads must stop with the Spring context")
	}

	@Test
	fun `Closing the Spring context closes its Kafka Streams instance exactly once`() {
		AnnotationConfigApplicationContext().use { context ->
			context.registerBean(KafkaStreamsSetup::class.java, Supplier { setup })
			context.refresh()
			setup.setupKafkaStreams("lifecycle-test")
		}

		setup.close()

		verifyOrder {
			streams.cleanUp()
			streams.setUncaughtExceptionHandler(any<org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler>())
			streams.start()
			streams.close()
		}
		verify(exactly = 1) { streams.close() }
	}

	@Test
	fun `Closing before initialization does not create a Kafka client`() {
		setup.close()
		setup.close()

		verify(exactly = 0) { setup.createKafkaStreams(any(), any()) }
		verify(exactly = 0) { streams.close() }
	}

	@Test
	fun `Initialization after shutdown is rejected without creating a Kafka client`() {
		setup.close()

		assertThrows(IllegalStateException::class.java) { setup.setupKafkaStreams("lifecycle-test") }

		verify(exactly = 0) { setup.createKafkaStreams(any(), any()) }
	}

	@Test
	fun `Repeated initialization cannot leak another Kafka Streams instance`() {
		try {
			setup.setupKafkaStreams("lifecycle-test")

			assertThrows(IllegalStateException::class.java) { setup.setupKafkaStreams("another-instance") }

			verify(exactly = 1) { setup.createKafkaStreams(any(), any()) }
		} finally {
			setup.close()
		}
	}

	@Test
	fun `A Kafka Streams instance that fails to start is still closed`() {
		every { streams.start() } throws IllegalStateException("Startup failed")

		AnnotationConfigApplicationContext().use { context ->
			context.registerBean(KafkaStreamsSetup::class.java, Supplier { setup })
			context.refresh()

			assertThrows(IllegalStateException::class.java) { setup.setupKafkaStreams("lifecycle-test") }
		}

		verify(exactly = 1) { streams.close() }
	}
}
