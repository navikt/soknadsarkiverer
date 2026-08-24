package no.nav.soknad.arkivering.soknadsarkiverer.kafka.bootstrapping

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.*
import no.nav.soknad.arkivering.soknadsarkiverer.service.TaskListService
import no.nav.soknad.arkivering.soknadsarkiverer.util.deserializeMsg
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executors

class KafkaBootstrapConsumer(
	private val taskListService: TaskListService,
	private val kafkaConfig: KafkaConfig
) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val processingTopic = kafkaConfig.topics.processingTopic
	private val processingTopicV3 = kafkaConfig.topics.processingTopicV3
	private val uuid = UUID.randomUUID().toString()
	private val noLoginTopic = kafkaConfig.topics.nologinSubmissionTopic
	private val loggedinTopic = kafkaConfig.topics.loggedinSubmissionTopic

	companion object {
		// Dedicated, small, fixed-size dispatcher for the concurrent bootstrap topic scans (issue
		// #265). Deliberately kept separate from Dispatchers.Default/IO: those scans each block a
		// thread for the whole scan (polling Kafka, joining a fresh consumer group), and sharing
		// Dispatchers.Default with unrelated coroutine work elsewhere in the app - notably
		// TaskListService's archiving dispatches, which also use Dispatchers.Default - would make the
		// two compete for the same limited core-thread pool under load. recreateState() only ever runs
		// 2 scans at a time (see below), so 4 threads is intentionally generous headroom, not arbitrary.
		// A single shared, static pool (rather than one per KafkaBootstrapConsumer instance) avoids
		// leaking threads across the many short-lived instances created during bootstrapping/replay/tests.
		private val bootstrapScanDispatcher = Executors.newFixedThreadPool(4) { runnable ->
			Thread(runnable, "kafka-bootstrap-scan").apply { isDaemon = true }
		}.asCoroutineDispatcher()
	}

	fun recreateState() {
		logger.info("Start recreating state")
		// Read both the retained v2 Avro processing-event history and the v3 JSON processing-event
		// history (issue #264). Production writers now only emit to v3 (`processingTopicV3`); v2 is
		// kept read-only purely for replaying pre-cutover history. Both histories are mapped to the
		// shared Avro `ProcessingEvent` representation and merged before applying the existing
		// highest-state-wins/finished-key filtering, so replay semantics stay identical regardless of
		// which topic a given event came from.
		//
		// These four topic scans are otherwise independent of each other, and each one can block for
		// up to `timeoutWhenNotFindingRecords` (45s) if its topic happens to be empty (see
		// KafkaRecordConsumer.hasTimedOut). Running all four sequentially would make bootstrap startup
		// additive in the number of scans - up to ~3 extra minutes in the worst case - even though
		// nothing about them requires that. However, full 4-way parallelism is not safe: the
		// logged-in/no-login scans use `finishedKeys` (merged from both processing-event topics) to
		// filter out already-finished tasks, and running them before that merged set is fully known
		// risks a race where an already-finished task slips through and gets re-added/re-archived by
		// `taskListService.addOrUpdateTask(...)` below. So we run the two independent stages
		// concurrently, but keep stage 2 (logged-in/no-login) waiting on the fully merged result of
		// stage 1 (v2+v3 processing events).
		val (finishedKeys, filteredUnfinishedProcessingEvents) = runBlocking {
			val processingRecordsV2 = async(bootstrapScanDispatcher) { getProcessingRecords() }
			val processingRecordsV3 = async(bootstrapScanDispatcher) { getProcessingRecordsV3() }

			val (finishedKeysV2, unfinishedProcessingRecordsV2) = processingRecordsV2.await()
			val (finishedKeysV3, unfinishedProcessingRecordsV3) = processingRecordsV3.await()

			val mergedFinishedKeys = HashSet(finishedKeysV2).also { it.addAll(finishedKeysV3) }

			val filteredUnfinished = (
				unfinishedProcessingRecordsV2.map { it.key() to it.value() } +
					unfinishedProcessingRecordsV3.map { it.key() to it.value().toAvroProcessingEvent() }
				)
				// A key can be unfinished from one topic's own perspective but finished according to
				// the other (e.g. RECEIVED/STARTED on v2, then ARCHIVED/FINISHED on v3 for the same
				// task). Re-apply the merged finished-key filter so finished-key filtering semantics
				// are the same as for a single-format history.
				.filter { (key, _) -> !mergedFinishedKeys.contains(key) }
				.fold(hashMapOf<Key, ProcessingEvent>()) { acc, (key, processingEvent) ->
					getHighestProcessingEventState(key, acc, processingEvent)
				}

			mergedFinishedKeys to filteredUnfinished
		}

		val (unfinishedLoggedinRecords, unfinishedNoLoginRecords) = runBlocking {
			val loggedinRecords = async(bootstrapScanDispatcher) { getUnfinishedLoggedinRecords(finishedKeys) }
			val noLoginRecords = async(bootstrapScanDispatcher) { getUnfinishedNoLoginRecords(finishedKeys) }

			loggedinRecords.await() to noLoginRecords.await()
		}
		logger.info("Recreating state, found a total of ${unfinishedLoggedinRecords.size} unfinished loggedin records")

		// For all not finished tasks with found received soknadsarkivschema trigger processing by adding to taskListService
		unfinishedLoggedinRecords
			.map { it.key() to it.value() }
			.shuffled() // Only one event at a time will be processed while restarting. Shuffle in case several pods go down,
			// so they don't process in the same order and can thus better parallelise.
			.forEach { (key, soknadsarkivschema) ->
				val state = filteredUnfinishedProcessingEvents[key] ?: ProcessingEvent(EventTypes.RECEIVED)

				taskListService.addOrUpdateTask(key, deserializeMsg( soknadsarkivschema), state.type, true)
			}

		logger.info("Recreating state, found a total of ${unfinishedNoLoginRecords.size} unfinished noLogin records")

		// For all not finished tasks with found received soknadsarkivschema trigger processing by adding to taskListService
		unfinishedNoLoginRecords
			.map { it.key() to it.value() }
			.shuffled() // Only one event at a time will be processed while restarting. Shuffle in case several pods go down,
			// so they don't process in the same order and can thus better parallelise.
			.forEach { (key, soknadsarkivschema) ->
				val state = filteredUnfinishedProcessingEvents[key] ?: ProcessingEvent(EventTypes.RECEIVED)

				taskListService.addOrUpdateTask(key, deserializeMsg( soknadsarkivschema), state.type, true)
			}
		logger.info("Finished recreating state, total unfinished states  ${unfinishedNoLoginRecords.size + unfinishedLoggedinRecords.size} processed")

	}


	private fun getUnfinishedLoggedinRecords(finishedKeys: HashSet<Key>): List<ConsumerRecord<Key, String>> {

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, String>> ->
			records.filter { !finishedKeys.contains(it.key()) }
		}

		// Uses its own consumer group id, distinct from getUnfinishedNoLoginRecords' - these two scans
		// are run concurrently (see recreateState), and sharing a single group id between concurrently
		// running consumers subscribed to different topics causes a Kafka consumer-group rebalance
		// race (partitions can end up misassigned between the two instances), silently dropping or
		// misdirecting records. Each BootstrapConsumer here still gets a fresh KafkaConsumer per call,
		// so this only needs to avoid colliding with a *concurrently active* group id, not with earlier
		// completed calls.
		return BootstrapConsumer.Builder<String>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-loggedin-$uuid")
			.withValueDeserializer(StringDeserializer())
			.forTopic(loggedinTopic)
			.getAllKafkaRecords()
	}

	private fun getUnfinishedNoLoginRecords(finishedKeys: HashSet<Key>): List<ConsumerRecord<Key, String>> {

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, String>> ->
			records.filter { !finishedKeys.contains(it.key()) }
		}

		// See getUnfinishedLoggedinRecords: kept on its own consumer group id since this runs
		// concurrently with it.
		return BootstrapConsumer.Builder<String>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-nologin-$uuid")
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

	/**
	 * Same shape as [getProcessingRecords], but for the v3 JSON processing-event history (issue #263's
	 * local [ProcessingEventJson] model). Kept as a separate topic read - rather than folded into
	 * [getProcessingRecords] - because the two topics use different deserializers (Avro vs JSON), but
	 * it mirrors the same finished-key/poison-message handling so both histories behave identically.
	 * Malformed v3 payloads are dropped via [PoisonSwallowingProcessingEventJsonDeserializer] (which
	 * wraps issue #263's [ProcessingEventJsonDeserializer]), the same way
	 * [PoisonSwallowingAvroDeserializer] drops malformed legacy payloads.
	 */
	private fun getProcessingRecordsV3(): Pair<HashSet<Key>, List<ConsumerRecord<Key, ProcessingEventJson>>> {
		val allFinishedKeys = hashSetOf<Key>()

		val keepUnfinishedRecordsFilter = { records: List<ConsumerRecord<Key, ProcessingEventJson>> ->

			val finishedKeys = records
				.filter { it.value().type == ProcessingEventType.FINISHED || it.value().type == ProcessingEventType.FAILURE }
				.map { it.key() }

			allFinishedKeys.addAll(finishedKeys)
			records.filter { !finishedKeys.contains(it.key()) }
		}

		val kafkaRecords = BootstrapConsumer.Builder<ProcessingEventJson>()
			.withFilter(keepUnfinishedRecordsFilter)
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-bootstrapping-processingevent-v3-$uuid")
			.withValueDeserializer(PoisonSwallowingProcessingEventJsonDeserializer())
			.forTopic(processingTopicV3)
			.getAllKafkaRecords()

		return allFinishedKeys to kafkaRecords
	}


	private fun getHighestProcessingEventState(
		key: Key,
		processingEventMap: HashMap<Key, ProcessingEvent>,
		processingEvent: ProcessingEvent
	): HashMap<Key, ProcessingEvent> {

		// Must read .type here, not the ProcessingEvent itself: processingEventMap[key] is a
		// ProcessingEvent (an Avro record), not an EventTypes enum value. Comparing a ProcessingEvent
		// directly against EventTypes.X below would always evaluate to false once a key has been
		// folded once (map[key] no longer null), silently discarding the "highest state seen so far"
		// guarantee - a lower-ranked event folded later for the same key (e.g. a key present in both
		// the v2 and v3 processing-event histories, issue #264/#265) would then incorrectly overwrite
		// an already-higher merged state instead of being ignored.
		val oldProcessingEventType = processingEventMap[key]?.type ?: EventTypes.RECEIVED

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
