package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroDeserializer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
import java.util.*
import java.util.concurrent.TimeUnit


@Configuration
class KafkaAdminConsumer(private val appConfiguration: AppConfiguration) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val inputTopic = appConfiguration.kafkaConfig.inputTopic
	private val processingTopic = appConfiguration.kafkaConfig.processingTopic
	private val messageTopic = appConfiguration.kafkaConfig.messageTopic


	internal fun getAllKafkaRecords(eventCollectionBuilder: EventCollection.Builder): List<KafkaEvent<String>> {
		return runBlocking {

			val records = awaitAll(
				getAllInputRecordsAsync(eventCollectionBuilder),
				getAllProcessingRecordsAsync(eventCollectionBuilder),
				getAllMessageRecordsAsync(eventCollectionBuilder)
			)
				.flatten()
				.map { KafkaEvent(it.sequence, it.innsendingKey, it.messageId, it.timestamp, it.type, it.payload.toString()) }

			val eventCollection = eventCollectionBuilder.build<String>()
			eventCollection.addEvents(records)
			eventCollection.getEvents()
		}
	}

	private fun getAllInputRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getAllKafkaRecords(inputTopic, "INPUT", PoisonSwallowingAvroDeserializer(), eventCollectionBuilder.build())
	}
	internal fun getAllProcessingRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getAllKafkaRecords(processingTopic, "PROCESSINGEVENT", PoisonSwallowingAvroDeserializer(), eventCollectionBuilder.build())
	}
	private fun getAllMessageRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getAllKafkaRecords(messageTopic, "MESSAGE", StringDeserializer(), eventCollectionBuilder.build())
	}

	private fun <T> getAllKafkaRecords(topic: String, recordType: String, valueDeserializer: Deserializer<T>, eventCollection: EventCollection<T>): List<KafkaEvent<T>> {
		try {
			val applicationId = "soknadsarkiverer-admin-$recordType-${UUID.randomUUID()}"

			KafkaConsumer<Key, T>(kafkaConfig(applicationId, valueDeserializer))
				.use {
					val startTime = System.currentTimeMillis()
					it.subscribe(listOf(topic))

					val records = loopUntilKafkaRecordsAreRetrieved(it, eventCollection)

					logger.info("For topic $topic: Found ${records.size} records in ${System.currentTimeMillis() - startTime}ms")
					return records
				}

		} catch (e: Exception) {
			logger.error("For topic $topic: Error getting $recordType", e)
			return emptyList()
		}
	}

	private fun <T> loopUntilKafkaRecordsAreRetrieved(kafkaConsumer: KafkaConsumer<Key, T>, eventCollection: EventCollection<T>): List<KafkaEvent<T>> {
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

	private fun <T> retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<KafkaEvent<T>> {
		logger.info("Retrieving Kafka records for ${kafkaConsumer.assignment()}")
		val records = mutableListOf<KafkaEvent<T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		logger.info("Found ${consumerRecords.count()} consumerRecords for ${kafkaConsumer.assignment()}")
		for (record in consumerRecords) {

			val messageId = StringDeserializer().deserialize("", record.headers().headers(MESSAGE_ID).firstOrNull()?.value()) ?: "null"

			if (record.key() != null && record.value() != null)
				records.add(KafkaEvent(record.key(), messageId, record.timestamp(), record.value()))
			else
				logger.error("For ${kafkaConsumer.assignment()}: Record had null attributes. Key='${record.key()}', value ${if (record.value() == null) "is" else "is not"} null")
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


	/**
	 * Explicitly swallow exceptions to avoid poison-pills preventing the whole topic to be read.
	 */
	class PoisonSwallowingAvroDeserializer<T : SpecificRecord> : SpecificAvroDeserializer<T>() {
		private val logger = LoggerFactory.getLogger(javaClass)

		override fun deserialize(topic: String, bytes: ByteArray): T? {
			return try {
				super.deserialize(topic, bytes)
			} catch (e: Exception) {
				logger.error("Unable to deserialize event on topic $topic\nByte Array: ${bytes.asList()}\nString representation: '${String(bytes)}'", e)
				null
			}
		}
	}
}

private typealias Key = String
