package no.nav.soknad.arkivering.soknadsarkiverer

import io.mockk.every
import io.mockk.mockk
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaSetupTest
import no.nav.soknad.arkivering.soknadsarkiverer.utils.ContainerizedKafka
import no.nav.soknad.arkivering.soknadsarkiverer.utils.KafkaContainerTeardown
import no.nav.soknad.arkivering.soknadsarkiverer.utils.KafkaListener
import no.nav.soknad.arkivering.soknadsarkiverer.utils.KafkaTestResources
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstanceFactoryContext
import org.opentest4j.MultipleFailuresError
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.ServerSocket
import java.time.Duration
import java.util.function.Supplier

@ActiveProfiles("test")
@SpringBootTest
@ExtendWith(KafkaTeardownTests.BeforeSpringTeardown::class)
class KafkaTeardownTests : ContainerizedKafka() {
	@MockitoBean
	lateinit var prometheusRegistry: PrometheusRegistry

	@Autowired
	lateinit var context: ConfigurableApplicationContext

	@Autowired
	lateinit var kafkaConfig: KafkaConfig

	@Test
	fun `test profile owns real Streams on the mapped broker`() {
		val streams = requireNotNull(context.getBean(KafkaSetupTest::class.java).kafkaStreams)
		assertEquals(kafkaContainer.bootstrapServers, kafkaConfig.brokers)
		assertFalse(streams.state().hasCompletedShutdown())
		assertTrue(streams.metrics().isNotEmpty())
	}

	@Test
	fun `listener timeout is surfaced and repeated cleanup succeeds without a broker`() {
		// Reserve a reachable TCP port without a Kafka server, avoiding another process taking it.
		ServerSocket(0).use { unavailableBroker ->
			val listener = KafkaListener(kafkaConfig.copy(brokers = "localhost:${unavailableBroker.localPort}"))
			try {
				assertThrows(IllegalStateException::class.java) { listener.close(Duration.ZERO) }
				assertTimeout(Duration.ofSeconds(50)) { listener.close(Duration.ofSeconds(45)) }
				listener.close()
				assertEquals(KafkaStreams.State.NOT_RUNNING, listener.kafkaStreams.state())
			} finally {
				listener.close()
			}
		}
	}

	@Test
	fun `context refresh failure closes Streams that already started`() {
		var owner: KafkaSetupTest? = null
		AnnotationConfigApplicationContext().use { failingContext ->
			failingContext.parent = context
			failingContext.environment.setActiveProfiles("test")
			failingContext.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
				override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
					if (bean is KafkaSetupTest) owner = bean
					return bean
				}
			})
			failingContext.register(KafkaSetupTest::class.java)
			failingContext.registerBean("failureAfterStreamsStartup", String::class.java, Supplier {
				assertNotNull(failingContext.getBean(KafkaSetupTest::class.java).kafkaStreams)
				error("Intentional context refresh failure")
			})
			assertThrows(BeanCreationException::class.java) { failingContext.refresh() }
			val startedOwner = requireNotNull(owner)
			assertEquals(KafkaStreams.State.NOT_RUNNING, startedOwner.kafkaStreams?.state())
			startedOwner.closeKafkaStreams()
		}
	}

	@Test
	fun `cleanup failure is surfaced after independent context and broker cleanup`() {
		val resources = KafkaTestResources()
		val applicationContext = AnnotationConfigApplicationContext().apply { refresh() }
		resources.context = applicationContext
		try {
			val failure = assertThrows(MultipleFailuresError::class.java) {
				resources.finishClass { throw AssertionError("Intentional cache eviction failure") }
			}
			assertTrue(failure.failures.any { it.message == "Intentional cache eviction failure" })
			assertFalse(applicationContext.isActive)
			assertTrue(resources.brokerStopped)
			resources.close()
			resources.close()
		} finally {
			resources.close()
		}
	}

	@Test
	fun `preconstruction isolates failed classes and engine end cleans the last failed instance`() {
		val originalResources = ContainerizedKafka.resources
		val registered = mutableMapOf<Any, KafkaTestResources>()
		val store = mockk<ExtensionContext.Store>()
		every { store.put(any(), any()) } answers {
			registered[firstArg()] = secondArg()
		}
		val extensionContext = mockk<ExtensionContext>()
		every { extensionContext.root } returns extensionContext
		every { extensionContext.getStore(any()) } returns store
		val factory = mockk<TestInstanceFactoryContext>()
		val callback = KafkaContainerTeardown()
		ContainerizedKafka.resources = null
		try {
			every { factory.testClass } returns FailedInstance::class.java
			callback.preConstructTestInstance(factory, extensionContext)
			val failedResources = ContainerizedKafka.currentResources
			val failedContext = object : AnnotationConfigApplicationContext() {
				override fun close() {
					super.close()
					error("Intentional failed-class cleanup error")
				}
			}.apply { refresh() }
			failedResources.context = failedContext

			// Simulate PER_CLASS construction failing: no afterAll callback runs for this owner.
			every { factory.testClass } returns NextInstance::class.java
			callback.preConstructTestInstance(factory, extensionContext)
			val nextResources = ContainerizedKafka.currentResources
			assertNotSame(failedResources, nextResources)
			assertNotSame(failedResources.broker, nextResources.broker)
			assertEquals(NextInstance::class.java, nextResources.owner)
			assertFalse(failedContext.isActive)
			assertTrue(failedResources.brokerStopped)
			assertNotNull(failedResources.deferredFailure)
			assertSame(failedResources, registered[FailedInstance::class.java])
			assertThrows(MultipleFailuresError::class.java) { registered.getValue(FailedInstance::class.java).close() }

			val lastContext = AnnotationConfigApplicationContext().apply { refresh() }
			nextResources.context = lastContext
			// The last instance can also fail; root-store cleanup must not need another class.
			registered.getValue(NextInstance::class.java).close()
			assertFalse(lastContext.isActive)
			assertTrue(nextResources.brokerStopped)
			assertNull(ContainerizedKafka.resources)
		} finally {
			registered.values.forEach { runCatching { it.close() } }
			ContainerizedKafka.resources = originalResources
		}
	}

	private class FailedInstance
	private class NextInstance

	class BeforeSpringTeardown : AfterAllCallback {
		override fun afterAll(context: ExtensionContext) {
			val resources = requireNotNull(ContainerizedKafka.resources)
			val applicationContext = requireNotNull(resources.context)
			val streams = requireNotNull(resources.setup?.kafkaStreams)
			assertTrue(applicationContext.isActive)
			assertTrue(resources.broker.isRunning)
			// Exercise the real callback before SpringExtension, as a changed meta-annotation could.
			KafkaContainerTeardown().afterAll(context)
			assertFalse(applicationContext.isActive)
			assertEquals(KafkaStreams.State.NOT_RUNNING, streams.state())
			assertFalse(resources.broker.isRunning)
		}
	}
}
