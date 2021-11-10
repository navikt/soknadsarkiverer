package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.KafkaConsumerBuilder
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

		logger.info("Recreating state, found a total of ${unfinishedInputRecords.size} unfinished input records")
		unfinishedInputRecords.chunked(250).forEach { sublist ->
			logger.info("Recreating state, found these ${sublist.size} unfinished input records: ${sublist.map { it.key() }}")
		}

		val filteredUnfinishedProcessingEvents = unfinishedProcessingRecords
			.map { it.key() to it.value() }
			.fold(hashMapOf<Key, ProcessingEvent>()) { acc, (key, processingEvent) ->
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


	private fun getUnfinishedInputRecords(finishedKeys: HashSet<Key>): List<ConsumerRecord<Key, Soknadarkivschema>> {

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, Soknadarkivschema>> ->
			records.filter { !finishedKeys.contains(it.key()) }
		}

		return BootstrapConsumer.Builder<Soknadarkivschema>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withAppConfiguration(appConfiguration)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-Input-$uuid")
			.withValueDeserializer(PoisonSwallowingAvroDeserializer())
			.forTopic(inputTopic)
			.getAllKafkaRecords()
	}

	private fun getProcessingRecords(): Pair<HashSet<Key>, List<ConsumerRecord<Key, ProcessingEvent>>> {
		val allFinishedKeys = hashSetOf<Key>()

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, ProcessingEvent>> ->

			val finishedKeys = records
				.filter { it.value().type == EventTypes.FINISHED || it.value().type == EventTypes.FAILURE }
				.map { it.key() }

			allFinishedKeys.addAll(finishedKeys)
			records.filter { !finishedKeys.contains(it.key()) }
		}

		val kafkaRecords = BootstrapConsumer.Builder<ProcessingEvent>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withAppConfiguration(appConfiguration)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-ProcessingEvent-$uuid")
			.withValueDeserializer(PoisonSwallowingAvroDeserializer())
			.forTopic(processingTopic)
			.getAllKafkaRecords()

		return allFinishedKeys to kafkaRecords
	}


	private fun getHighestProcessingEventState(
		key: Key,
		processingEventMap: HashMap<Key, ProcessingEvent>,
		processingEvent: ProcessingEvent
	): HashMap<Key, ProcessingEvent> {

		val oldProcessingEventType = processingEventMap[key] ?: EventTypes.RECEIVED

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


	override fun getEnforcedTimeoutInMs() = appConfiguration.kafkaConfig.bootstrappingTimeout.toInt() * 1000

	override fun addRecords(newRecords: List<ConsumerRecord<Key, T>>) {
		records.addAll(newRecords)
		records = filter.invoke(records).toMutableList()
	}

	override fun getRecords(): List<ConsumerRecord<Key, T>> = records


	class Builder<T>(private var filter: ((List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>)? = null) :
		KafkaConsumerBuilder<T, ConsumerRecord<Key, T>>() {

		fun withFilter(filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>) =
			apply { this.filter = filter }

		override fun getAllKafkaRecords() =
			BootstrapConsumer(appConfiguration!!, kafkaGroupId!!, deserializer!!, topic!!, filter!!)
				.getAllKafkaRecords()
	}
}
