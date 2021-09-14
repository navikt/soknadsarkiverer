package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroDeserializer
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

class KafkaBootstrapConsumer(
	private val appConfiguration: AppConfiguration,
	private val taskListService: TaskListService
) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val inputTopic = appConfiguration.kafkaConfig.inputTopic
	private val processingTopic = appConfiguration.kafkaConfig.processingTopic
	private val uuid = UUID.randomUUID().toString()


	fun recreateState() {
		val (finishedKeys, unfinishedProcessingRecords) = getProcessingRecords()
		val unfinishedInputRecords = getUnfinishedInputRecords(finishedKeys)

		val filteredUnfinishedProcessingEvents = unfinishedProcessingRecords
			.map { it.key() to it.value() }
			.fold(emptyMap<Key, ProcessingEvent>()) { acc, (key, processingEvent) ->
				getHighestProcessingEventState(key, acc, processingEvent)
			}

		unfinishedInputRecords
			.map { it.key() to it.value() }
			.shuffled() // Only one event at a time will be processed while restarting. Shuffle in case several pods go down,
									// so they don't process in the same order and can thus better parallelise.
			.forEach { (key, soknadsarkivschema) ->
				val state = filteredUnfinishedProcessingEvents[key] ?: ProcessingEvent(EventTypes.RECEIVED)

				taskListService.addOrUpdateTask(key, soknadsarkivschema, state.type, true)
			}
	}


	private fun getUnfinishedInputRecords(finishedKeys: List<Key>): List<ConsumerRecord<Key, Soknadarkivschema>> {
		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, Soknadarkivschema>> ->
			records.filter { !finishedKeys.contains(it.key()) }
		}

		val deserializer = PoisonSwallowingAvroDeserializer<Soknadarkivschema>()
		return getAllKafkaRecords(inputTopic, "Input", deserializer, keepUnfinishedRecordsFilter)
	}

	private fun getProcessingRecords(): Pair<MutableList<Key>, List<ConsumerRecord<Key, ProcessingEvent>>> {
		val allFinishedKeys = mutableListOf<Key>()

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, ProcessingEvent>> ->

			val finishedKeys = records
				.filter { it.value().type == EventTypes.FINISHED || it.value().type == EventTypes.FAILURE }
				.map { it.key() }

			allFinishedKeys.addAll(finishedKeys)
			records.filter { !finishedKeys.contains(it.key()) }
		}

		val kafkaRecords = getAllKafkaRecords(
			processingTopic,
			"ProcessingEvent",
			PoisonSwallowingAvroDeserializer(),
			keepUnfinishedRecordsFilter
		)
		return allFinishedKeys to kafkaRecords
	}

	private fun <T> getAllKafkaRecords(
		topic: String,
		recordType: String,
		valueDeserializer: Deserializer<T>,
		filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>
	): List<ConsumerRecord<Key, T>> {

		try {
			val applicationId = "soknadsarkiverer-bootstrapping-$recordType-$uuid"
			logger.info("About to bootstrap records from $topic")

			KafkaConsumer<Key, T>(kafkaConfig(applicationId, valueDeserializer))
				.use {
					val startTime = System.currentTimeMillis()
					it.subscribe(listOf(topic))

					val records = loopUntilKafkaRecordsAreRetrieved(it, filter)

					logger.info("For topic $topic: Found ${records.size} records in ${System.currentTimeMillis() - startTime}ms")
					return records
				}

		} catch (e: Exception) {
			logger.error("For topic $topic: Error getting $recordType", e)
			return emptyList()
		}
	}

	private fun <T> loopUntilKafkaRecordsAreRetrieved(
		kafkaConsumer: KafkaConsumer<Key, T>,
		filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>
	): List<ConsumerRecord<Key, T>> {

		val startTime = System.currentTimeMillis()
		val timeout = appConfiguration.kafkaConfig.bootstrappingTimeout.toInt() * 1000
		var records = mutableListOf<ConsumerRecord<Key, T>>()

		while (true) {
			val newRecords = retrieveKafkaRecords(kafkaConsumer)
			val shouldStop = shouldStop(records, newRecords)

			records.addAll(newRecords)
			records = filter.invoke(records).toMutableList()

			if (shouldStop)
				break
			if (hasTimedOut(startTime, timeout, records)) {
				logger.warn("For topic ${kafkaConsumer.assignment()}: Was still consuming Kafka records " +
					"${System.currentTimeMillis() - startTime} ms after starting. Has read ${records.size} records." +
					"Aborting consumption.")
				break
			}
			if (newRecords.isEmpty())
				TimeUnit.MILLISECONDS.sleep(100)
		}
		return records
	}

	private fun hasTimedOut(startTime: Long, timeout: Int, records: List<*>): Boolean {

		val shouldEnforceTimeout = timeout > 0
		val hasTimedOut = System.currentTimeMillis() > startTime + timeout

		val hasTimedOutWithoutRecords = System.currentTimeMillis() > startTime + timeoutWhenNotFindingRecords
		val hasNotReadRecords = records.isEmpty()

		return shouldEnforceTimeout && hasTimedOut || hasNotReadRecords && hasTimedOutWithoutRecords
	}

	private fun shouldStop(previousRecords: List<*>, newRecords: List<*>) =
		previousRecords.isNotEmpty() && newRecords.isEmpty()

	private fun <T> retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<ConsumerRecord<Key, T>> {
		logger.info("Retrieving Kafka records for ${kafkaConsumer.assignment()}")
		val records = mutableListOf<ConsumerRecord<Key, T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		logger.info("Found ${consumerRecords.count()} consumerRecords for ${kafkaConsumer.assignment()}")
		for (record in consumerRecords) {

			if (record.key() != null && record.value() != null)
				records.add(record)
			else
				logger.error("For ${kafkaConsumer.assignment()}: Record had null attributes. Key='${record.key()}', " +
					"value ${if (record.value() == null) "is" else "is not"} null")
		}
		return records
	}

	private fun getHighestProcessingEventState(key: Key,
																						 oldProcessingEventMap: Map<Key, ProcessingEvent>,
																						 processingEvent: ProcessingEvent): Map<Key, ProcessingEvent> {
		val oldProcessingEventType = oldProcessingEventMap[key] ?: EventTypes.RECEIVED

		val highestState =
			if (oldProcessingEventType == EventTypes.FINISHED || processingEvent.type == EventTypes.FINISHED) {
				EventTypes.FINISHED
			} else if (oldProcessingEventType == EventTypes.FAILURE || processingEvent.type == EventTypes.FAILURE) {
				EventTypes.FAILURE
			} else if (oldProcessingEventType == EventTypes.ARCHIVED || processingEvent.type == EventTypes.ARCHIVED) {
				EventTypes.ARCHIVED
			} else if (oldProcessingEventType == EventTypes.STARTED || processingEvent.type == EventTypes.STARTED) {
				EventTypes.STARTED
			} else {
				EventTypes.RECEIVED
			}

		val processingEventMap = oldProcessingEventMap.toMutableMap()
		processingEventMap[key] = ProcessingEvent(highestState)
		return processingEventMap
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
				logger.error("Unable to deserialize event on topic $topic\nByte Array: ${bytes.asList()}\n" +
					"String representation: '${String(bytes)}'", e)
				null
			}
		}
	}
}

private typealias Key = String
private const val timeoutWhenNotFindingRecords = 30 * 1000
