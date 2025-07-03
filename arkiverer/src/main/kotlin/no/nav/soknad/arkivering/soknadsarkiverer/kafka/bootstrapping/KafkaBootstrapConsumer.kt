package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.util.deserializeMsg
import no.nav.soknad.arkivering.soknadsarkiverer.util.translate
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.util.*

class KafkaBootstrapConsumer(
	private val taskListService: TaskListService,
	private val kafkaConfig: KafkaConfig
) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val mainTopic = kafkaConfig.topics.mainTopic
	private val processingTopic = kafkaConfig.topics.processingTopic
	private val uuid = UUID.randomUUID().toString()
	private val noLoginTopic = kafkaConfig.topics.nologinSubmissionTopic

	fun recreateState() {
		val (finishedKeys, unfinishedProcessingRecords) = getProcessingRecords()
		val unfinishedMainRecords = getUnfinishedMainRecords(finishedKeys)

		logger.info("Recreating state, found a total of ${unfinishedMainRecords.size} unfinished main records")
		unfinishedMainRecords.chunked(250).forEach { sublist ->
			logger.info("Recreating state, found these ${sublist.size} unfinished main records: ${sublist.map { it.key() }}")
		}

		val filteredUnfinishedProcessingEvents = unfinishedProcessingRecords
			.map { it.key() to it.value() }
			.fold(hashMapOf<Key, ProcessingEvent>()) { acc, (key, processingEvent) ->
				getHighestProcessingEventState(key, acc, processingEvent)
			}

		// For all not finished tasks with found received soknadsarkivschema trigger processing by adding to taskListService
		unfinishedMainRecords
			.map { it.key() to it.value() }
			.shuffled() // Only one event at a time will be processed while restarting. Shuffle in case several pods go down,
									// so they don't process in the same order and can thus better parallelise.
			.forEach { (key, soknadsarkivschema) ->
				val state = filteredUnfinishedProcessingEvents[key] ?: ProcessingEvent(EventTypes.RECEIVED)

				taskListService.addOrUpdateTask(key, translate(soknadsarkivschema), state.type, true)
			}

		val unfinishedNoLoginRecords = getUnfinishedNoLoginRecords(finishedKeys)

		// For all not finished tasks with found received soknadsarkivschema trigger processing by adding to taskListService
		unfinishedNoLoginRecords
			.map { it.key() to it.value() }
			.shuffled() // Only one event at a time will be processed while restarting. Shuffle in case several pods go down,
			// so they don't process in the same order and can thus better parallelise.
			.forEach { (key, soknadsarkivschema) ->
				val state = filteredUnfinishedProcessingEvents[key] ?: ProcessingEvent(EventTypes.RECEIVED)

				taskListService.addOrUpdateTask(key, deserializeMsg( soknadsarkivschema), state.type, true)
			}

	}


	private fun getUnfinishedMainRecords(finishedKeys: HashSet<Key>): List<ConsumerRecord<Key, Soknadarkivschema>> {

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, Soknadarkivschema>> ->
			records.filter { !finishedKeys.contains(it.key()) }
		}

		return BootstrapConsumer.Builder<Soknadarkivschema>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-main-$uuid")
			.withValueDeserializer(PoisonSwallowingAvroDeserializer())
			.forTopic(mainTopic)
			.getAllKafkaRecords()
	}


	private fun getUnfinishedNoLoginRecords(finishedKeys: HashSet<Key>): List<ConsumerRecord<Key, String>> {

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, String>> ->
			records.filter { !finishedKeys.contains(it.key()) }
		}

		return BootstrapConsumer.Builder<String>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-main-$uuid")
			.withValueDeserializer(StringDeserializer())
			.forTopic(noLoginTopic)
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
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-processingevent-$uuid")
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
	private val kafkaConfig: KafkaConfig,
	kafkaGroupId: String,
	deserializer: Deserializer<T>,
	topic: String,
	private val filter: (List<ConsumerRecord<Key, T>>) -> List<ConsumerRecord<Key, T>>
) : KafkaRecordConsumer<T, ConsumerRecord<Key, T>>(kafkaConfig, kafkaGroupId, deserializer, topic) {

	private var records = mutableListOf<ConsumerRecord<Key, T>>()


	override fun getEnforcedTimeoutInMs() = kafkaConfig.bootstrappingTimeout.toInt() * 1000

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
			BootstrapConsumer(kafkaConfig!!, kafkaGroupId!!, deserializer!!, topic!!, filter!!)
				.getAllKafkaRecords()
	}
}
