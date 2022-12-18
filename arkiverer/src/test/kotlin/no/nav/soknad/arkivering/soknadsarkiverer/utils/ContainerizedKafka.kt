package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.*

open class ContainerizedKafka {

	companion object {
		private val kafkaConfig: KafkaConfig

		private val kafkaContainer: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka"))
			.withNetworkAliases("kafka-broker")

		init {
			val factoryBean = YamlPropertiesFactoryBean()
			factoryBean.setResources(ClassPathResource("application.yml"))

			val properties = factoryBean.getObject()

			val propertySource = MapConfigurationPropertySource(properties)
			val binder = Binder(propertySource)

			kafkaConfig = binder.bind("kafka", KafkaConfig::class.java).get()
		}

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			start()
			registry.add(
				"kafka.brokers", kafkaContainer::getBootstrapServers
			)
		}

		private fun start() {
			kafkaContainer.start()

			listOf(
				kafkaConfig.topics.mainTopic,
				kafkaConfig.topics.processingTopic,
				kafkaConfig.topics.messageTopic,
				kafkaConfig.topics.metricsTopic
			)
				.forEach { createTopic(it) }
		}

		@JvmStatic
		@AfterAll
		fun close() {
			println("Stopping Kafka Container")
			kafkaContainer.stop()
		}


		private fun createTopic(topic: String) {
			val topicCommand =
				"/usr/bin/kafka-topics --create --bootstrap-server=localhost:9092 --replication-factor 1 --partitions 1 --topic $topic"

			try {
				val result = kafkaContainer.execInContainer("/bin/sh", "-c", topicCommand)
				if (result.exitCode != 0) {
					println("Kafka Container logs:\n${kafkaContainer.logs}")
					fail("Failed to create topic '$topic'. Error:\n${result.stderr}")
				}
			} catch (e: Exception) {
				e.printStackTrace()
				fail("Failed to create topic '$topic'")
			}
		}
	}
}
