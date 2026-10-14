package no.nav.soknad.arkivering.soknadsarkiverer

import io.mockk.mockk
import no.nav.soknad.arkivering.soknadsarkiverer.config.ApplicationState
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaPublisher
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaSetupTest
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.supervision.ArchivingMetrics
import no.nav.soknad.arkivering.soknadsarkiverer.utils.ContainerizedKafka
import org.apache.kafka.streams.KafkaStreams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.kafka.KafkaContainer

/**
 * Guards the ordering the Kafka test fixture relies on: closing the Spring context closes the
 * test-profile Kafka Streams while the broker is still running, and only then stops the broker.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = [KafkaTeardownTests.MinimalKafkaContext::class])
class KafkaTeardownTests : ContainerizedKafka() {

	@Autowired
	lateinit var kafkaSetup: KafkaSetupTest

	@Autowired
	lateinit var broker: KafkaContainer

	@Autowired
	lateinit var kafkaConfig: KafkaConfig

	@Test
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	fun `closing the context closes Streams before the broker stops`() {
		streams = requireNotNull(kafkaSetup.kafkaStreams)
		stoppedBroker = broker
		assertEquals(broker.bootstrapServers, kafkaConfig.brokers)
		assertFalse(streams!!.state().hasCompletedShutdown())
	}

	companion object {
		private var streams: KafkaStreams? = null
		private var stoppedBroker: KafkaContainer? = null

		// Runs after SpringExtension has closed the context marked dirty by the test method.
		@JvmStatic
		@AfterAll
		fun verifyShutdownOrder() {
			assertEquals(true, BrokerStateAtStreamsShutdown.brokerRunning)
			assertEquals(KafkaStreams.State.NOT_RUNNING, requireNotNull(streams).state())
			assertFalse(requireNotNull(stoppedBroker).isRunning)
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(KafkaConfig::class)
	@Import(KafkaSetupTest::class, BrokerStateAtStreamsShutdown::class)
	class MinimalKafkaContext {
		@Bean
		fun applicationState() = ApplicationState()

		@Bean
		fun taskListService() = mockk<TaskListService>(relaxed = true)

		@Bean
		fun kafkaPublisher() = mockk<KafkaPublisher>(relaxed = true)

		@Bean
		fun archivingMetrics() = mockk<ArchivingMetrics>(relaxed = true)
	}

	class BrokerStateAtStreamsShutdown : DestructionAwareBeanPostProcessor {
		private var broker: KafkaContainer? = null

		override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
			if (bean is KafkaContainer) broker = bean
			return bean
		}

		override fun postProcessBeforeDestruction(bean: Any, beanName: String) {
			if (bean is KafkaSetupTest) brokerRunning = broker?.isRunning
		}

		companion object {
			@Volatile
			var brokerRunning: Boolean? = null
		}
	}
}
