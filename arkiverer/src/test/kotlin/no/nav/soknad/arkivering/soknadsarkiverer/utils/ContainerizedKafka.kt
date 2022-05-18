package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.config.*
import org.junit.jupiter.api.fail
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.io.Closeable

@Configuration
class ContainerizedKafka : Closeable {
	private val kafkaContainer: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka"))
		.withNetworkAliases("kafka-broker")

	init {
		kafkaContainer.start()
		listOf(kafkaInputTopic, kafkaProcessingTopic, kafkaMessageTopic, kafkaMetricsTopic)
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

	@Primary
	@Bean
	fun kafkaAppConfiguration() =
		AppConfiguration(kafkaConfig = AppConfiguration.KafkaConfig(kafkaBrokers = kafkaContainer.bootstrapServers))
}
