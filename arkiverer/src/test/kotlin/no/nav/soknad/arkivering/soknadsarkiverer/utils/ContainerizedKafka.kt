package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.config.*
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import org.junit.jupiter.api.fail
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.io.Closeable


@Configuration
//@ContextConfiguration(initializers = {ContainerizedKafka::EnvInitializer.class})
class ContainerizedKafka : Closeable {

	companion object {

		 val kafkaContainer: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka"))
			.withNetworkAliases("kafka-broker")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add(
				"kafka.brokers", kafkaContainer::getBootstrapServers
			)
		}
	}

	init {
		kafkaContainer.start()
		listOf(kafkaMainTopic, kafkaProcessingTopic, kafkaMessageTopic, kafkaMetricsTopic)
			.forEach { createTopic(it) }
	}

	override fun close() {
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


	internal class EnvInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
		override fun initialize(applicationContext: ConfigurableApplicationContext) {
			TestPropertyValues.of(
				java.lang.String.format(
					"kafka.brokers.url=",
					kafkaContainer.bootstrapServers
				)
			).applyTo(applicationContext)
		}
	}

/*
	@Bean()
	@Primary
	fun kafkaAppConfiguration(kafkaConfig: KafkaConfig) = KafkaConfig(kafkaConfig.applicationId,
																																	  kafkaContainer.bootstrapServers,
																																		kafkaConfig.bootstrappingTimeout,
																																		kafkaConfig.delayBeforeKafkaInitialization,
																																		kafkaConfig.security,
																																		kafkaConfig.topics,
																																		kafkaConfig.schemaRegistry)
*/
}

