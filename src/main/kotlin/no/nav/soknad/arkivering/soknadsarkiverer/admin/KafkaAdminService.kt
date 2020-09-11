package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes.FINISHED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
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

@Configuration
class KafkaAdminService(private val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)

	fun getAllEvents(): List<KafkaEvent> {

		val processingEvents = getKafkaEvents(appConfiguration.kafkaConfig.processingTopic, "PROCESSINGEVENT", createProcessingEventSerde().deserializer()) { value: ProcessingEvent -> value.getType().name }
		val inputEvents = getKafkaEvents(appConfiguration.kafkaConfig.inputTopic, "INPUT", createSoknadarkivschemaSerde().deserializer())
		val messageEvents = getKafkaEvents(appConfiguration.kafkaConfig.messageTopic, "MESSAGE", StringDeserializer()) { value -> "MESSAGE $value" }

		return listOf(processingEvents, inputEvents, messageEvents)
			.flatten()
			.sortedBy { it.timestamp }
	}

	fun getUnfinishedEvents(): List<KafkaEvent> {

		val processingEventTriples = getAllKafkaRecords(appConfiguration.kafkaConfig.processingTopic, "PROCESSINGEVENT", createProcessingEventSerde().deserializer())
		val inputEvents = getKafkaEvents(appConfiguration.kafkaConfig.inputTopic, "INPUT", createSoknadarkivschemaSerde().deserializer())
		val messageEvents = getKafkaEvents(appConfiguration.kafkaConfig.messageTopic, "MESSAGE", StringDeserializer()) { value -> "MESSAGE $value" }


		val finishedKeys = processingEventTriples
			.filter { (_, _, processingEvent) -> processingEvent.getType() == FINISHED }
			.map { (key, _, _) -> key }


		val filteredProcessingEvents = processingEventTriples
			.filter { (key, _, _) -> !finishedKeys.contains(key) }
			.map { (key, timestamp, processingEvent) -> KafkaEvent(key, timestamp, processingEvent.getType().name) }

		val filteredInputEvents = inputEvents.filter { !finishedKeys.contains(it.key) }
		val filteredMessageEvents = messageEvents.filter { !finishedKeys.contains(it.key) }

		return listOf(filteredProcessingEvents, filteredInputEvents, filteredMessageEvents)
			.flatten()
			.sortedBy { it.timestamp }
	}

	fun getAllEventsForKey(key: String) = getAllEvents().filter { it.key == key }


	private fun <T> getKafkaEvents(topic: String, recordType: String, valueDeserializer: Deserializer<T>, recordTypeGetter: (T) -> String = { recordType }): List<KafkaEvent> {
		val records = getAllKafkaRecords(topic, recordType, valueDeserializer)

		return records
			.map { (key, timestamp, value) -> KafkaEvent(key, timestamp, recordTypeGetter.invoke(value)) }
	}


	private fun <T> getAllKafkaRecords(topic: String, recordType: String, valueDeserializer: Deserializer<T>): List<Triple<Key, LocalDateTime, T>> {
		val records = mutableListOf<Triple<Key, LocalDateTime, T>>()
		try {
			val applicationId = "soknadsarkiverer-admin-$recordType-${UUID.randomUUID()}"

			KafkaConsumer<Key, T>(kafkaConfig(applicationId, valueDeserializer)).use {
				it.subscribe(listOf(topic))
				records.addAll(retrieveKafkaRecords(it))
			}

		} catch (e: Exception) {
			logger.error("Error getting $recordType", e)
		}
		return records
	}

	private fun <T> retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<Triple<Key, LocalDateTime, T>> {
		val records = mutableListOf<Triple<Key, LocalDateTime, T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		for (record in consumerRecords) {
			val timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault())

			records.add(Triple(record.key(), timestamp, record.value()))
		}
		return records
	}

	private fun <T> kafkaConfig(applicationId: String, valueDeserializer: Deserializer<T>) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[ConsumerConfig.GROUP_ID_CONFIG] = applicationId
		it[ConsumerConfig.GROUP_INSTANCE_ID_CONFIG] = UUID.randomUUID().toString()
		it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
//		it[ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG] = 100
//		it[ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG] = 100
		//it[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG] = 1000
//		it[ConsumerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG] = 10
//		it[ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG] = 10
		//it[ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG] = 10
//		it[ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG] = 10
//		it[ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG] = 10
		//it[ConsumerConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG] = 10
		//it[ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG] = 60000
//		it[ConsumerConfig.RETRY_BACKOFF_MS_CONFIG] = 10
//		it[ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG] = 20
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
