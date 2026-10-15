package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.kafka.Topics
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.kafka.KafkaContainer
import java.util.concurrent.TimeUnit

/**
 * Kafka broker owned by the Spring test context. Spring Boot starts container beans before all
 * other beans and stops them after all other beans are destroyed, so Kafka Streams and other
 * application clients always close while the broker is still running.
 */
@TestConfiguration(proxyBeanMethods = false)
class KafkaTestContainerConfiguration {

	@Bean
	fun kafkaContainer(environment: Environment): KafkaContainer =
		KafkaContainer("apache/kafka-native:4.2.2").also {
			// Kafka Streams does not create its source topics, so they must exist before the application starts.
			it.start()
			createTopics(it.bootstrapServers, Binder.get(environment).bind("kafka.topics", Topics::class.java).get())
		}

	@Bean
	fun kafkaBrokerProperties(kafkaContainer: KafkaContainer) = DynamicPropertyRegistrar {
		it.add("kafka.brokers", kafkaContainer::getBootstrapServers)
	}

	private fun createTopics(bootstrapServers: String, topics: Topics) {
		val names = with(topics) {
			listOf(
				processingTopic, messageTopic, arkiveringstilbakemeldingTopic, metricsTopic,
				nologinSubmissionTopic, loggedinSubmissionTopic, processingTopicV3, metricsTopicV3
			)
		}
		Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
			admin.createTopics(names.map { NewTopic(it, 1, 1.toShort()) }).all().get(30, TimeUnit.SECONDS)
		}
	}
}
