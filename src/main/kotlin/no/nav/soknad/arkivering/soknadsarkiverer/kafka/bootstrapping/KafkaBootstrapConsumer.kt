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

		val applicationId = "soknadsarkiverer-bootstrapping-Input-$uuid"
		val consumer = BootstrapConsumer(appConfiguration, applicationId, keepUnfinishedRecordsFilter)

		return consumer.getAllKafkaRecords(inputTopic, PoisonSwallowingAvroDeserializer())
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

		val applicationId = "soknadsarkiverer-bootstrapping-ProcessingEvent-$uuid"
		val consumer = BootstrapConsumer(appConfiguration, applicationId, keepUnfinishedRecordsFilter)

		val kafkaRecords = consumer.getAllKafkaRecords(processingTopic, PoisonSwallowingAvroDeserializer())
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


private class BootstrapConsumer<T>(
	private val appConfiguration: AppConfiguration,
	private val applicationId: String,
	private val filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>
) : KafkaRecordConsumer<T, ConsumerRecord<Key, T>>(appConfiguration) {

	private var records = mutableListOf<ConsumerRecord<Key, T>>()


	override fun getApplicationId() = applicationId

	override fun getTimeout() = appConfiguration.kafkaConfig.bootstrappingTimeout.toInt() * 1000

	override fun shouldStop(hasPreviouslyReadRecords: Boolean, newRecords: List<*>) =
		hasPreviouslyReadRecords && newRecords.isEmpty()

	override fun addRecords(newRecords: List<ConsumerRecord<Key, T>>) {
		records.addAll(newRecords)
		records = filter.invoke(records).toMutableList()
	}

	override fun getRecords(): List<ConsumerRecord<Key, T>> = records
}
