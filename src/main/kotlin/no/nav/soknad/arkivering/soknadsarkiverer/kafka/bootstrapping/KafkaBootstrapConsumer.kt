package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaRecordConsumer
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.Key
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.PoisonSwallowingAvroDeserializer
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Deserializer
import org.slf4j.LoggerFactory
import java.util.*

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
		logger.info("Recreating state, found these unfinished input records: ${unfinishedInputRecords.map { it.key() }}")

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

		val finishedKeysSet = finishedKeys.toHashSet()
		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, Soknadarkivschema>> ->
			records.filter { !finishedKeysSet.contains(it.key()) }
		}

		return BootstrapConsumer.Builder<Soknadarkivschema>()
			.withAppConfiguration(appConfiguration)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-Input-$uuid")
			.withValueDeserializer(PoisonSwallowingAvroDeserializer())
			.withFilter(keepUnfinishedRecordsFilter)
			.forTopic(inputTopic)
			.getAllKafkaRecords()
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

		val kafkaRecords = BootstrapConsumer.Builder<ProcessingEvent>()
			.withAppConfiguration(appConfiguration)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-ProcessingEvent-$uuid")
			.withValueDeserializer(PoisonSwallowingAvroDeserializer())
			.withFilter(keepUnfinishedRecordsFilter)
			.forTopic(processingTopic)
			.getAllKafkaRecords()

		return allFinishedKeys to kafkaRecords
	}


	private fun getHighestProcessingEventState(
		key: Key,
		oldProcessingEventMap: Map<Key, ProcessingEvent>,
		processingEvent: ProcessingEvent
	): Map<Key, ProcessingEvent> {

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
}


private class BootstrapConsumer<T> private constructor(
	private val appConfiguration: AppConfiguration,
	kafkaGroupId: String,
	deserializer: Deserializer<T>,
	topic: String,
	private val filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>
) : KafkaRecordConsumer<T, ConsumerRecord<Key, T>>(appConfiguration, kafkaGroupId, deserializer, topic) {

	private var records = mutableListOf<ConsumerRecord<Key, T>>()


	override fun getTimeout() = appConfiguration.kafkaConfig.bootstrappingTimeout.toInt() * 1000

	override fun shouldStop(hasPreviouslyReadRecords: Boolean, newRecords: List<*>) =
		hasPreviouslyReadRecords && newRecords.isEmpty()

	override fun addRecords(newRecords: List<ConsumerRecord<Key, T>>) {
		records.addAll(newRecords)
		records = filter.invoke(records).toMutableList()
	}

	override fun getRecords(): List<ConsumerRecord<Key, T>> = records


	data class Builder<T>(
		private var appConfiguration: AppConfiguration? = null,
		private var kafkaGroupId: String? = null,
		private var filter: ((List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>)? = null,
		private var deserializer: Deserializer<T>? = null,
		private var topic: String? = null
	) {

		fun withAppConfiguration(appConfiguration: AppConfiguration) = apply { this.appConfiguration = appConfiguration }
		fun withKafkaGroupId(kafkaGroupId: String) = apply { this.kafkaGroupId = kafkaGroupId }
		fun withFilter(filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>) = apply { this.filter = filter }
		fun withValueDeserializer(deserializer: Deserializer<T>) = apply { this.deserializer = deserializer }
		fun forTopic(topic: String) = apply { this.topic = topic }

		fun getAllKafkaRecords(): List<ConsumerRecord<Key, T>> {
			if (appConfiguration == null || kafkaGroupId == null || deserializer == null || topic == null || filter == null)
				throw Exception("When constructing BootstrapConsumer: Expected appConfiguration, kafkaGroupId, deserializer, " +
					"topic and filter to be set!")
			return BootstrapConsumer(appConfiguration!!, kafkaGroupId!!, deserializer!!, topic!!, filter!!).getAllKafkaRecords()
		}
	}
}
