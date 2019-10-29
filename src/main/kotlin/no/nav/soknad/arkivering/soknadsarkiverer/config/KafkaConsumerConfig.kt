package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FileStorageRetrievingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Consumed
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams

@EnableKafkaStreams
@Configuration
class KafkaConsumerConfig(val applicationProperties: ApplicationProperties,
													val fileStorageRetrievingService: FileStorageRetrievingService,
													val messageConverter: MessageConverter,
													val joarkArchiver: JoarkArchiver) {

	@Bean
	fun kafkaStreamTopology(streamsBuilder: StreamsBuilder): Topology {

		streamsBuilder.stream<String, ArchivalData>(applicationProperties.kafkaTopic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
			.mapValues { archivalData -> Pair(archivalData, fileStorageRetrievingService.getFilesFromFileStorage(archivalData)) }
			.mapValues { dataPair -> messageConverter.createJoarkData(dataPair.first, dataPair.second) }
			.foreach { _, joarkData -> joarkArchiver.putDataInJoark(joarkData) }

		return streamsBuilder.build()
	}
}
