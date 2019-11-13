package no.nav.soknad.arkivering.soknadsarkiverer.config

import no.nav.soknad.arkivering.dto.ArchivalData
import no.nav.soknad.arkivering.soknadsarkiverer.converter.MessageConverter
import no.nav.soknad.arkivering.soknadsarkiverer.service.FileStorageRetrievingService
import no.nav.soknad.arkivering.soknadsarkiverer.service.JoarkArchiver
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.DeserializationExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.processor.ProcessorContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams
import org.springframework.kafka.support.serializer.JsonDeserializer
import java.util.*

@EnableKafkaStreams
@Configuration
class KafkaConsumerConfig(val applicationProperties: ApplicationProperties,
													val fileStorageRetrievingService: FileStorageRetrievingService,
													val messageConverter: MessageConverter,
													val joarkArchiver: JoarkArchiver) {

	@Bean
	fun kafkaConfig() = Properties().also {
		it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JsonDeserializer::class.java

		it[StreamsConfig.APPLICATION_ID_CONFIG] = "soknadsarkiverer"
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = applicationProperties.kafkaBootstrapServers
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = KafkaExceptionHandler::class.java
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
	fun kStream(topology: Topology, kafkaExceptionHandler: KafkaExceptionHandler): KafkaStreams {
		val kafkaStreams = KafkaStreams(topology, kafkaConfig())
		kafkaStreams.setUncaughtExceptionHandler(kafkaExceptionHandler)
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
		return kafkaStreams
	}

	@Bean
	fun kafkaExceptionHandler() = KafkaExceptionHandler()
}

class KafkaExceptionHandler : Thread.UncaughtExceptionHandler, DeserializationExceptionHandler {
	private val topic = "dlq"

	override fun handle(context: ProcessorContext, record: ConsumerRecord<ByteArray, ByteArray>, exception: Exception): DeserializationExceptionHandler.DeserializationHandlerResponse {
		println("Exception from kafka deserialization")
		kafkaProducer().send(ProducerRecord(topic, record.timestamp().toInt(), record.key(), record.value()))
		// TODO: What if we can't produce to retry topic?
		return DeserializationExceptionHandler.DeserializationHandlerResponse.CONTINUE
	}

	override fun configure(configs: MutableMap<String, *>) {
	}

	override fun uncaughtException(t: Thread, e: Throwable) {
		// TODO: Put on retry topic. Put what, though?
		println("uncaughtException '$e'")
	}


	@Bean
	fun kafkaProducer() = KafkaProducer<ByteArray, ByteArray>(kafkaConfigMap())

	private fun kafkaConfigMap(): MutableMap<String, Any> {
		return HashMap<String, Any>().also {
			it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = "localhost:3333"
			it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
			it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
		}
	}
}
