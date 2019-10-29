package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FileStorageRetrievingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig.APPLICATION_ID_CONFIG
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Consumed
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams
import org.springframework.kafka.support.serializer.JsonDeserializer
import java.util.*

//@EnableKafka
@EnableKafkaStreams
@Configuration
class KafkaConsumerConfig(private val applicationProperties: ApplicationProperties) {

	@Bean
	fun consumerConfigs() = Properties().also {
		it[GROUP_ID_CONFIG] = "soknadsarkiverer"
		it[APPLICATION_ID_CONFIG] = "default"
		it[BOOTSTRAP_SERVERS_CONFIG] = applicationProperties.kafka.bootstrapServers
		it[KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[VALUE_DESERIALIZER_CLASS_CONFIG] = JsonDeserializer::class.java
	}

/*
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
*/

	@Bean
	fun kafkaStreamTopology(streamsBuilder: StreamsBuilder, fileStorageRetrievingService: FileStorageRetrievingService, joarkArchiver: JoarkArchiver): Topology {

		val topic = "privat-soknadInnsendt-sendsoknad-v1-q0"

		streamsBuilder.stream<String, ArchivalData>(topic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
			.mapValues { archivalData -> Pair(archivalData, fileStorageRetrievingService.getFilesFromFileStorage(archivalData)) }
			.mapValues { dataPair -> MessageConverter().createJoarkData(dataPair.first, dataPair.second) }
			.foreach { _, joarkData -> joarkArchiver.putDataInJoark(joarkData) }

		return streamsBuilder.build()
	}

//	@Bean
//	fun kStream(topology: Topology) = KafkaStreams(topology, consumerConfigs())
}
