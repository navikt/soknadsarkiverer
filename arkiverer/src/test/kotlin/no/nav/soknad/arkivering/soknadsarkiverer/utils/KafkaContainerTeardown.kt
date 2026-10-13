package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaSetupTest
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstanceFactoryContext
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback
import org.opentest4j.MultipleFailuresError
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestContextManager
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit

class CaptureKafkaTestContext : ApplicationContextInitializer<ConfigurableApplicationContext> {
	override fun initialize(context: ConfigurableApplicationContext) {
		val resources = ContainerizedKafka.currentResources
		resources.context = context
		context.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
			override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
				if (bean is KafkaSetupTest) resources.setup = bean
				return bean
			}

			override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
				if (bean is KafkaSetupTest) bean.kafkaStreams?.let(resources::captureClientIds)
				return bean
			}
		})
	}
}

class KafkaContainerTeardown : TestInstancePreConstructCallback, AfterAllCallback {
	override fun preConstructTestInstance(factoryContext: TestInstanceFactoryContext, context: ExtensionContext) {
		ContainerizedKafka.resources?.takeIf { it.owner != factoryContext.testClass }?.let { stale ->
			ContainerizedKafka.resources = null
			try {
				stale.finishClass(stale::evictContext)
			} catch (failure: Throwable) {
				// Report cleanup failure at engine end without failing the next, unrelated class.
				stale.deferredFailure = failure
			}
		}
		val resources = ContainerizedKafka.currentResources
		resources.owner = factoryContext.testClass
		// PER_CLASS construction can fail before JUnit enables AfterAllCallback.
		context.root.getStore(NAMESPACE).put(factoryContext.testClass, resources)
	}

	override fun afterAll(context: ExtensionContext) {
		val resources = ContainerizedKafka.resources ?: return
		ContainerizedKafka.resources = null
		resources.finishClass(resources::evictContext)
	}

	companion object {
		private val NAMESPACE = ExtensionContext.Namespace.create(KafkaContainerTeardown::class.java)
	}
}

internal class KafkaTestResources : AutoCloseable {
	val broker = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.1"))
		.withNetworkAliases("kafka-broker")
	var owner: Class<*>? = null
	var context: ConfigurableApplicationContext? = null
	var setup: KafkaSetupTest? = null
	var listener: KafkaListener? = null
	var brokerStopped = false
		private set
	private val clientIds = mutableSetOf<String>()
	var deferredFailure: Throwable? = null

	fun evictContext() {
		owner?.let {
			TestContextManager(it).testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE)
		}
	}

	fun captureClientIds(streams: KafkaStreams) {
		clientIds += streams.metrics().keys.mapNotNull { it.tags()["client-id"] }
	}

	fun finishClass(evictContext: () -> Unit) {
		val failures = mutableListOf<Throwable>()
		fun attempt(action: () -> Unit) {
			try {
				action()
			} catch (failure: Throwable) {
				failures += failure
			}
		}

		setup?.kafkaStreams?.let(::captureClientIds)
		listener?.kafkaStreams?.let(::captureClientIds)
		attempt { listener?.close() }
		// Normally SpringExtension has already closed the dirty context. Close explicitly if
		// inherited/meta-annotations change callback order; never reload an evicted context.
		attempt { context?.close() }
		attempt(evictContext)
		attempt { setup?.closeKafkaStreams() }
		attempt { setup?.shutdownFailure?.let { throw it } }

		val closed = runCatching { verifyClosed() }
		closed.exceptionOrNull()?.let { failures += it }
		if (closed.isSuccess) {
			attempt {
				println("Kafka teardown verified: context closed, Streams terminal, owned threads stopped; stopping broker")
				broker.stop()
				brokerStopped = true
			}
		}
		if (failures.isNotEmpty()) throw MultipleFailuresError("Kafka fixture teardown failed", failures)
	}

	private fun verifyClosed() {
		check(context?.isActive != true) { "Spring context is still active before broker stop" }
		val streams = listOfNotNull(setup?.kafkaStreams, listener?.kafkaStreams)
		streams.forEach {
			check(it.state() == KafkaStreams.State.NOT_RUNNING || it.state() == KafkaStreams.State.ERROR) {
				"Kafka Streams is still ${it.state()} before broker stop"
			}
		}
		check(streams.isEmpty() || clientIds.isNotEmpty()) { "No owned Kafka client IDs captured" }
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		var liveThreads = ownedLiveThreads()
		while (liveThreads.isNotEmpty() && System.nanoTime() < deadline) {
			Thread.sleep(10)
			liveThreads = ownedLiveThreads()
		}
		check(liveThreads.isEmpty()) {
			"Owned Kafka threads still alive before broker stop: ${liveThreads.map { it.name }}"
		}
	}

	private fun ownedLiveThreads() = Thread.getAllStackTraces().keys.filter { thread ->
		thread.isAlive && clientIds.any { thread.name.contains(it) }
	}

	override fun close() {
		if (ContainerizedKafka.resources === this) ContainerizedKafka.resources = null
		val failures = mutableListOf<Throwable>()
		deferredFailure?.let { failures += it }
		try {
			if (!brokerStopped) finishClass(::evictContext)
		} catch (failure: Throwable) {
			failures += failure
		} finally {
			if (!brokerStopped) {
				// Engine-end fallback also removes a broker whose client never completed shutdown.
				try {
					broker.stop()
					brokerStopped = true
				} catch (failure: Throwable) {
					failures += failure
				}
			}
		}
		if (failures.isNotEmpty()) throw MultipleFailuresError("Kafka engine-end cleanup failed", failures)
	}
}
