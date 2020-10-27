package no.nav.soknad.arkivering.soknadsarkiverer.admin

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.avroschemas.EventTypes.FINISHED
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.MESSAGE_ID
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.getConfigForKey
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.header.Headers
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

	val topics = listOf(
		appConfiguration.kafkaConfig.inputTopic,
		appConfiguration.kafkaConfig.processingTopic,
		appConfiguration.kafkaConfig.messageTopic)


	fun getAllEvents() = getAllEvents { true }

	private fun getAllEvents(itemFiler: (KafkaEventRaw<*>) -> Boolean): List<KafkaEvent> {
		return createContentEventList(getAllKafkaRecords(topics), itemFiler)
	}

	fun getUnfinishedEvents(): List<KafkaEvent> {
		val events = getAllKafkaRecords(topics)

		val finishedKeys = events
			.filter { it.payload is ProcessingEvent }
			.filter { (_, _, _, processingEvent) -> (processingEvent as ProcessingEvent).getType() == FINISHED }
			.map { (key, _, _, _) -> key }


		return createContentEventList(events) { event -> !finishedKeys.contains(event.key) }
	}

	fun getAllEventsForKey(key: String) = getAllEvents { it.key == key }

	fun search(searchPhrase: Regex) = getAllEvents { it.payload.toString().contains(searchPhrase) }


	private fun createContentEventList(events: List<KafkaEventRaw<Any>>,
																		 itemFiler: (KafkaEventRaw<*>) -> Boolean): List<KafkaEvent> {

		val sequence = generateSequence(0) { it + 1 }
			.take(events.size).toList()

		return events
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
		val events = getAllKafkaRecords(topics)
			.firstOrNull { it.messageId == messageId }
		if (events != null)
			return events.payload.toString()

		throw NoSuchElementException("Could not find message with id $messageId")
	}


	internal fun getAllKafkaRecords(topics: List<String>): List<KafkaEventRaw<Any>> {
		val records = mutableListOf<KafkaEventRaw<Any>>()
		try {
			val applicationId = "soknadsarkiverer-admin-${UUID.randomUUID()}"

			KafkaConsumer<Key, Any>(kafkaConfig(applicationId)).use {
				it.subscribe(topics)
				records.addAll(retrieveKafkaRecords(it))
			}

		} catch (e: Exception) {
			logger.error("Error when consuming Kafka events", e)
		}
		return records
	}

	private fun retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, Any>): List<KafkaEventRaw<Any>> {
		val records = mutableListOf<KafkaEventRaw<Any>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		for (record in consumerRecords) {
			val timestamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault())

			val messageId = StringDeserializer().deserialize("", record.headers().headers(MESSAGE_ID).firstOrNull()?.value()) ?: "null"
			records.add(KafkaEventRaw(record.key(), messageId, timestamp, record.value()))
		}
		return records
	}

	private fun kafkaConfig(applicationId: String) = Properties().also {
		it[kafkaInputTopic] = appConfiguration.kafkaConfig.inputTopic
		it[kafkaProcessingTopic] = appConfiguration.kafkaConfig.processingTopic
		it[kafkaMessageTopic] = appConfiguration.kafkaConfig.messageTopic
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[ConsumerConfig.GROUP_ID_CONFIG] = applicationId
		it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
		it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = MultiDeserializer::class.java

		if (appConfiguration.kafkaConfig.secure == "TRUE") {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
			it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
			it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
		}
	}
}


class MultiDeserializer : Deserializer<Any> {
	private lateinit var inputTopic: String
	private lateinit var processingEventLogTopic: String
	private lateinit var messageTopic: String
	private lateinit var schemaRegistryUrl: String
	private lateinit var processingEventSerdeDeserializer: Deserializer<ProcessingEvent>
	private lateinit var soknadarkivschemaSerdeDeserializer: Deserializer<Soknadarkivschema>
	private lateinit var stringSerdeDeserializer: Deserializer<String>

	override fun configure(configs: Map<String, *>, isKey: Boolean) {
		schemaRegistryUrl = getConfigForKey(configs, AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG) as String
		inputTopic = getConfigForKey(configs, kafkaInputTopic) as String
		processingEventLogTopic = getConfigForKey(configs, kafkaProcessingTopic) as String
		messageTopic = getConfigForKey(configs, kafkaMessageTopic) as String

		processingEventSerdeDeserializer = createProcessingEventSerde().deserializer()
		soknadarkivschemaSerdeDeserializer = createSoknadarkivschemaSerde().deserializer()
		stringSerdeDeserializer = StringDeserializer()
	}

	override fun deserialize(topic: String, headers: Headers, data: ByteArray) = deserialize(topic, data)

	override fun deserialize(topic: String, data: ByteArray): Any {
		return when (topic) {
			inputTopic -> soknadarkivschemaSerdeDeserializer.deserialize(topic, data)
			processingEventLogTopic -> processingEventSerdeDeserializer.deserialize(topic, data)
			messageTopic -> stringSerdeDeserializer.deserialize(topic, data)
			else -> throw Exception("Found no deserializer for topic '$topic'")
		}
	}

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createSoknadarkivschemaSerde(): SpecificAvroSerde<Soknadarkivschema> = createAvroSerde()

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}
}


private typealias Key = String

const val kafkaInputTopic =	"KAFKA_INPUT_TOPIC"
const val kafkaProcessingTopic = "KAFKA_PROCESSING_TOPIC"
const val kafkaMessageTopic = "KAFKA_MESSAGE_TOPIC"
