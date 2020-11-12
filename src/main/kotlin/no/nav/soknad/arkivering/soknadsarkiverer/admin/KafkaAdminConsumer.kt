package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import java.util.concurrent.TimeUnit

@Configuration
class KafkaAdminConsumer(private val appConfiguration: AppConfiguration) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val inputTopic = appConfiguration.kafkaConfig.inputTopic
	private val processingTopic = appConfiguration.kafkaConfig.processingTopic
	private val messageTopic = appConfiguration.kafkaConfig.messageTopic


	internal fun getAllKafkaRecords(eventCollectionBuilder: EventCollection.Builder): List<KafkaEventRaw<*>> {
		return runBlocking {
			val records = awaitAll(
				getAllInputRecordsAsync(eventCollectionBuilder),
				getAllProcessingRecordsAsync(eventCollectionBuilder),
				getAllMessageRecordsAsync(eventCollectionBuilder)
			)

			val eventCollection = eventCollectionBuilder.build<Any>()
			eventCollection.addEvents(records.flatten() as List<KafkaEventRaw<Any>>) // TODO: Fix warning with Reified?

			eventCollection.getEvents()
		}
	}
	private fun getAllInputRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getAllKafkaRecords(inputTopic, "INPUT", createSoknadarkivschemaSerde().deserializer(), eventCollectionBuilder.build())
	}
	internal fun getAllProcessingRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getAllKafkaRecords(processingTopic, "PROCESSINGEVENT", createProcessingEventSerde().deserializer(), eventCollectionBuilder.build())
	}
	private fun getAllMessageRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getAllKafkaRecords(messageTopic, "MESSAGE", StringDeserializer(), eventCollectionBuilder.build())
	}

	private fun <T> getAllKafkaRecords(topic: String, recordType: String, valueDeserializer: Deserializer<T>, eventCollection: EventCollection<T>): List<KafkaEventRaw<T>> {
		try {
			val applicationId = "soknadsarkiverer-admin-$recordType-${UUID.randomUUID()}"

			KafkaConsumer<Key, T>(kafkaConfig(applicationId, valueDeserializer))
				.use {
					val startTime = System.currentTimeMillis()
					it.subscribe(listOf(topic))

					val records = loopUntilKafkaRecordsAreRetrieved(it, eventCollection)

					logger.info("Found ${records.size} records in ${System.currentTimeMillis() - startTime}ms")
					return records
				}

		} catch (e: Exception) {
			logger.error("Error getting $recordType", e)
			return emptyList()
		}
	}

	private fun <T> loopUntilKafkaRecordsAreRetrieved(kafkaConsumer: KafkaConsumer<Key, T>, eventCollection: EventCollection<T>): List<KafkaEventRaw<T>> {
		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val records = retrieveKafkaRecords(kafkaConsumer)

			val shouldStop = eventCollection.addEvents(records)
			if (shouldStop)
				break
			TimeUnit.MILLISECONDS.sleep(100)
		}
		return eventCollection.getEvents()
	}

	private fun <T> retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<KafkaEventRaw<T>> {
		logger.info("Retrieving Kafka records")
		val records = mutableListOf<KafkaEventRaw<T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		logger.info("Found ${consumerRecords.count()} consumerRecords")
		for (record in consumerRecords) {
			val timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault())

			val messageId = StringDeserializer().deserialize("", record.headers().headers(MESSAGE_ID).firstOrNull()?.value()) ?: "null"

			if (record.key() != null)
				records.add(KafkaEventRaw(record.key(), messageId, timestamp, record.value()))
			else
				logger.error("Key was null for record: $record")
		}
		return records
	}

	private fun <T> kafkaConfig(applicationId: String, valueDeserializer: Deserializer<T>) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[ConsumerConfig.GROUP_ID_CONFIG] = applicationId
		it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
		it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = valueDeserializer::class.java

		if (appConfiguration.kafkaConfig.secure == "TRUE") {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
			it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
			it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
		}
	}

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createSoknadarkivschemaSerde(): SpecificAvroSerde<Soknadarkivschema> = createAvroSerde()

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}
}

private typealias Key = String
