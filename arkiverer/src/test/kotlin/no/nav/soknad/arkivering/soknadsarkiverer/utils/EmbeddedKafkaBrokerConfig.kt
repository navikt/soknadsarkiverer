package no.nav.soknad.arkivering.soknadsarkiverer.utils

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class EmbeddedKafkaBrokerConfig {

	@Value("\${spring.embedded.kafka.brokers:localhost:9092}")
	private val kafkaBrokers: String = "localhost:9092"

	@Bean(name = ["testAppConfiguration"])
	@Primary
	fun appConfiguration(): AppConfiguration {
		return AppConfiguration(kafkaConfig = AppConfiguration.KafkaConfig(servers = kafkaBrokers))
	}
}
