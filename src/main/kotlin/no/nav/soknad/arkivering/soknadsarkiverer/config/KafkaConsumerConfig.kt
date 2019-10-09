package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.support.serializer.JsonDeserializer

@EnableKafka
@Configuration
class KafkaConsumerConfig(private val applicationProperties: ApplicationProperties) {

	@Bean
	fun consumerConfigs() = HashMap<String, Any>().also {
			it[GROUP_ID_CONFIG] = "soknadsarkiverer"
			it[BOOTSTRAP_SERVERS_CONFIG] = applicationProperties.kafka.bootstrapServers
			it[KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
			it[VALUE_DESERIALIZER_CLASS_CONFIG] = JsonDeserializer::class.java
		}

	@Bean
	fun consumerFactory() = DefaultKafkaConsumerFactory<String, ArchivalData>(consumerConfigs(), StringDeserializer(), JsonDeserializer(ArchivalData::class.java))

	@Bean
	fun kafkaListenerContainerFactory() = ConcurrentKafkaListenerContainerFactory<String, ArchivalData>().also {
			it.consumerFactory = consumerFactory()
		}
}
