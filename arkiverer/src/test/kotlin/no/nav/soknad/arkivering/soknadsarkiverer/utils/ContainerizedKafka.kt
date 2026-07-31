package no.nav.soknad.arkivering.soknadsarkiverer.utils

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConfig
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.TimeUnit


open class ContainerizedKafka {


	companion object {
		private val kafkaConfig: KafkaConfig

		init {
			val factoryBean = YamlPropertiesFactoryBean()
			factoryBean.setResources(ClassPathResource("application.yml"))

			val properties = factoryBean.getObject()
			val mutableMap = properties as MutableMap<*, *>

			val propertySource = MapConfigurationPropertySource(mutableMap)
			val binder = Binder(propertySource)

			kafkaConfig = binder.bind("kafka", KafkaConfig::class.java).get()
		}

		// @Container
		val kafkaContainer: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.1"))
			.withNetworkAliases("kafka-broker")

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
				kafkaConfig.topics.processingTopic,
				kafkaConfig.topics.messageTopic,
				kafkaConfig.topics.arkiveringstilbakemeldingTopic,
				kafkaConfig.topics.metricsTopic,
				kafkaConfig.topics.nologinSubmissionTopic,
				kafkaConfig.topics.loggedinSubmissionTopic,
				kafkaConfig.topics.processingTopicV3
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


	fun <T> putDataOnTopic(
		key: String, value: T, headers: Headers, topic: String,
		kafkaProducer: KafkaProducer<String, T>
	): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add(MESSAGE_ID, UUID.randomUUID().toString().toByteArray())
		headers.forEach { producerRecord.headers().add(it) }

		return kafkaProducer
			.send(producerRecord)
			.get(1000, TimeUnit.MILLISECONDS) // Blocking call
	}


	fun kafkaConfigMap(kafkaConfig: KafkaConfig): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "mock://mocked-scope"
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
		}
	}


}

