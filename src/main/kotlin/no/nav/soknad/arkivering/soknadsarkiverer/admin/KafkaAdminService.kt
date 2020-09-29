package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes.FINISHED
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
import java.time.*
import java.util.*
import kotlin.NoSuchElementException

@Configuration
class KafkaAdminService(private val appConfiguration: AppConfiguration) {

	private val logger = LoggerFactory.getLogger(javaClass)

	fun getAllEvents() = getAllEvents { true }

	private fun getAllEvents(itemFiler: (KafkaEventRaw<*>) -> Boolean): List<KafkaEvent> {

		val processingEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.processingTopic, "PROCESSINGEVENT", createProcessingEventSerde().deserializer())
		val inputEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.inputTopic, "INPUT", createSoknadarkivschemaSerde().deserializer())
		val messageEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.messageTopic, "MESSAGE", StringDeserializer())

		return createContentEventList(processingEvents, inputEvents, messageEvents, itemFiler)
	}

	fun getUnfinishedEvents(): List<KafkaEvent> {

		val processingEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.processingTopic, "PROCESSINGEVENT", createProcessingEventSerde().deserializer())
		val inputEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.inputTopic, "INPUT", createSoknadarkivschemaSerde().deserializer())
		val messageEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.messageTopic, "MESSAGE", StringDeserializer())


		val finishedKeys = processingEvents
			.filter { (_, _, _, processingEvent) -> processingEvent.getType() == FINISHED }
			.map { (key, _, _, _) -> key }


		return createContentEventList(processingEvents, inputEvents, messageEvents) { event -> !finishedKeys.contains(event.key) }
	}

	fun getAllEventsForKey(key: String) = getAllEvents { it.key == key }


	private fun createContentEventList(processingEvents: List<KafkaEventRaw<ProcessingEvent>>,
																		 inputEvents: List<KafkaEventRaw<Soknadarkivschema>>,
																		 messageEvents: List<KafkaEventRaw<String>>,
																		 itemFiler: (KafkaEventRaw<*>) -> Boolean): List<KafkaEvent> {

		val sequence = generateSequence(0) { it + 1 }
			.take(processingEvents.size + inputEvents.size + messageEvents.size).toList()

		return listOf(processingEvents, inputEvents, messageEvents)
			.flatten()
			.filter { itemFiler.invoke(it) }
			.sortedBy { it.timestamp }
			.zip(sequence) { event, seq -> KafkaEvent(seq, event.key, event.messageId, getTypeRepresentation(event.payload), event.timestamp.toInstant(ZoneOffset.UTC).toEpochMilli()) }
	}

	private fun getTypeRepresentation(data: Any): String {
		return when(data) {
			is ProcessingEvent -> data.getType().name
			is Soknadarkivschema -> "INPUT"
			is String -> {
				"MESSAGE " + when {
					data.startsWith("ok", true) -> "Ok"
					data.startsWith("Exception", true) -> "Exception"
					else -> "Unknown"
				}
			}
			else -> "UNKNOWN"
		}
	}


	fun content(messageId: String): String {
		val inputEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.inputTopic, "INPUT", createSoknadarkivschemaSerde().deserializer())
			.firstOrNull { it.messageId == messageId }
		if (inputEvents != null)
			return inputEvents.payload.toString()

		val messageEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.messageTopic, "MESSAGE", StringDeserializer())
			.firstOrNull { it.messageId == messageId }
		if (messageEvents != null)
			return messageEvents.payload

		val processingEvents = getAllKafkaRecords(appConfiguration.kafkaConfig.processingTopic, "PROCESSINGEVENT", createProcessingEventSerde().deserializer())
			.firstOrNull { it.messageId == messageId }
		if (processingEvents != null)
			return processingEvents.payload.toString()

		throw NoSuchElementException("Could not find message with id $messageId")
	}


	internal fun <T> getAllKafkaRecords(topic: String, recordType: String, valueDeserializer: Deserializer<T>): List<KafkaEventRaw<T>> {
		val records = mutableListOf<KafkaEventRaw<T>>()
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

	private fun <T> retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<KafkaEventRaw<T>> {
		val records = mutableListOf<KafkaEventRaw<T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		for (record in consumerRecords) {
			val timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault())

			val messageId = StringDeserializer().deserialize("", record.headers().headers(MESSAGE_ID).firstOrNull()?.value()) ?: "null"
			records.add(KafkaEventRaw(record.key(), messageId, timestamp, record.value()))
		}
		return records
	}

	private fun <T> kafkaConfig(applicationId: String, valueDeserializer: Deserializer<T>) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[ConsumerConfig.GROUP_ID_CONFIG] = applicationId
		it[ConsumerConfig.GROUP_INSTANCE_ID_CONFIG] = UUID.randomUUID().toString()
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
