package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FileStorageRetrievingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.DeserializationExceptionHandler
import org.apache.kafka.streams.errors.ProductionExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.processor.ProcessorContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.EnableKafkaStreams
import org.springframework.kafka.support.serializer.JsonDeserializer
import java.util.*

@EnableKafkaStreams
@EnableKafka
@Configuration
class KafkaConsumerConfig(val applicationProperties: ApplicationProperties,
													val fileStorageRetrievingService: FileStorageRetrievingService,
													val messageConverter: MessageConverter,
													val joarkArchiver: JoarkArchiver) {

	@Bean
	fun consumerConfigs() = Properties().also {
		it[ConsumerConfig.GROUP_ID_CONFIG] = "soknadsarkiverer"
		it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = "localhost:9092"
		it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JsonDeserializer::class.java

		it[StreamsConfig.APPLICATION_ID_CONFIG] = "default"
		it[StreamsConfig.NUM_STREAM_THREADS_CONFIG] = 1
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
		it[StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
	}

	@Bean
	fun kafkaStreamTopology(streamsBuilder: StreamsBuilder): Topology {

		streamsBuilder.stream<String, ArchivalData>(applicationProperties.kafkaTopic, Consumed.with(Serdes.String(), ArchivalDataSerde()))
			.mapValues { archivalData -> Pair(archivalData, fileStorageRetrievingService.getFilesFromFileStorage(archivalData)) }
			.mapValues { dataPair -> messageConverter.createJoarkData(dataPair.first, dataPair.second) }
			.foreach { _, joarkData -> joarkArchiver.putDataInJoark(joarkData) }

		return streamsBuilder.build()
	}

	@Bean
	fun kStream(streamsBuilder: StreamsBuilder, kafkaExceptionHandler: KafkaExceptionHandler): KafkaStreams {
		val kafkaStreams = KafkaStreams(kafkaStreamTopology(streamsBuilder), consumerConfigs())
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler)
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		return kafkaStreams
	}

	@Bean
	fun kafkaExceptionHandler() = KafkaExceptionHandler()
}


class KafkaExceptionHandler : Thread.UncaughtExceptionHandler, DeserializationExceptionHandler, ProductionExceptionHandler {
	override fun handle(record: ProducerRecord<ByteArray, ByteArray>?, exception: Exception?): ProductionExceptionHandler.ProductionExceptionHandlerResponse {
		println("Exception from kafka production")
		return ProductionExceptionHandler.ProductionExceptionHandlerResponse.CONTINUE
	}

	override fun handle(context: ProcessorContext?, record: ConsumerRecord<ByteArray, ByteArray>?, exception: Exception?): DeserializationExceptionHandler.DeserializationHandlerResponse {
		println("Exception from kafka deserialization")
		return DeserializationExceptionHandler.DeserializationHandlerResponse.CONTINUE
	}

	override fun configure(configs: MutableMap<String, *>?) {
	}

	override fun uncaughtException(t: Thread, e: Throwable) {
		println("uncaughtException '$e'")
	}
}
