package no.nav.soknad.arkivering.soknadsarkiverer.admin

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.soknadsarkiverer.admin.EventCollection.TimeSelector.BEFORE
import no.nav.soknad.arkivering.soknadsarkiverer.kafka.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.stereotype.Service
import java.util.*

@Service
class KafkaAdminConsumer(private val kafkaConfig: KafkaConfig) {

	private val mainTopic = kafkaConfig.topics.mainTopic
	private val processingTopic = kafkaConfig.topics.processingTopic
	private val messageTopic = kafkaConfig.topics.messageTopic
	private val metricsTopic = kafkaConfig.topics.metricsTopic


	internal fun getAllKafkaRecords(eventCollectionBuilder: EventCollection.Builder): List<KafkaEvent<String>> {
		val records = runBlocking {
			awaitAll(
				getAllMainRecordsAsync(eventCollectionBuilder),
				getAllProcessingRecordsAsync(eventCollectionBuilder),
				getAllMessageRecordsAsync(eventCollectionBuilder),
				getAllMetricsRecordsAsync(eventCollectionBuilder)
			)
		}.flatten()
		return collectKafkaRecords(records, eventCollectionBuilder)
	}

	internal fun getProcessingAndMetricsKafkaRecords(
		eventCollectionBuilder: EventCollection.Builder
	): List<KafkaEvent<String>> {

		val records = runBlocking {
			awaitAll(
				getAllProcessingRecordsAsync(eventCollectionBuilder),
				getAllMetricsRecordsAsync(eventCollectionBuilder)
			)
		}.flatten()
		return collectKafkaRecords(records, eventCollectionBuilder)
	}

	private fun collectKafkaRecords(
		records: List<KafkaEvent<out Any>>,
		eventCollectionBuilder: EventCollection.Builder
	): List<KafkaEvent<String>> {

		val kafkaEventRecords = records
			.map { KafkaEvent(it.sequence, it.innsendingKey, it.messageId, it.timestamp, it.type, it.content.toString()) }

		val eventCollection = eventCollectionBuilder.build<String>()
		eventCollection.addEvents(kafkaEventRecords)
		return eventCollection.getEvents()
	}

	private fun getAllMainRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getRecords(mainTopic, "main", PoisonSwallowingAvroDeserializer(), eventCollectionBuilder)
	}
	internal fun getAllProcessingRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getRecords(processingTopic, "processingevent", PoisonSwallowingAvroDeserializer(), eventCollectionBuilder)
	}
	private fun getAllMessageRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getRecords(messageTopic, "message", StringDeserializer(), eventCollectionBuilder)
	}
	private fun getAllMetricsRecordsAsync(eventCollectionBuilder: EventCollection.Builder) = GlobalScope.async {
		getRecords(metricsTopic, "metrics", PoisonSwallowingAvroDeserializer(), eventCollectionBuilder)
	}

	private fun <T> getRecords(
		topic: String,
		recordType: String,
		deserializer: Deserializer<T>,
		eventCollectionBuilder: EventCollection.Builder
	): List<KafkaEvent<T>> {

		return AdminConsumer.Builder<T>()
			.withEventCollection(eventCollectionBuilder.build())
			.withKafkaConfig(kafkaConfig)
			.withKafkaGroupId("soknadsarkiverer-admin-$recordType-${UUID.randomUUID()}")
			.withValueDeserializer(deserializer)
			.forTopic(topic)
			.getAllKafkaRecords()
	}
}


private class AdminConsumer<T> private constructor(
	kafkaConfig: KafkaConfig,
	kafkaGroupId: String,
	deserializer: Deserializer<T>,
	topic: String,
	private val eventCollection: EventCollection<T>
) : KafkaRecordConsumer<T, KafkaEvent<T>>(kafkaConfig, kafkaGroupId, deserializer, topic) {

	private var collectionWasSatisfiedOnLastRecordAddition = false


	override fun getEnforcedTimeoutInMs() = 30 * 1000

	override fun shouldStop(newRecords: List<ConsumerRecord<Key, T>>) = super.shouldStop(newRecords) ||
			collectionWasSatisfiedOnLastRecordAddition && eventCollection.getTimeSelector() != BEFORE


	override fun addRecords(newRecords: List<ConsumerRecord<Key, T>>) {
		val kafkaEvents = newRecords.map { createKafkaEvent(it) }
		collectionWasSatisfiedOnLastRecordAddition = eventCollection.addEvents(kafkaEvents)
	}

	private fun createKafkaEvent(record: ConsumerRecord<Key, T>): KafkaEvent<T> {
		val recordHeaderMessageId = record.headers().headers(MESSAGE_ID).firstOrNull()?.value()
		val messageId = StringDeserializer().deserialize("", recordHeaderMessageId) ?: "null"

		return KafkaEvent(record.key(), messageId, record.timestamp(), record.value())
	}

	override fun getRecords(): List<KafkaEvent<T>> = eventCollection.getEvents()


	class Builder<T>(private var eventCollection: EventCollection<T>? = null) : KafkaConsumerBuilder<T, KafkaEvent<T>>() {

		fun withEventCollection(eventCollection: EventCollection<T>) = apply { this.eventCollection = eventCollection }

		override fun getAllKafkaRecords() =
			AdminConsumer(kafkaConfig!!, kafkaGroupId!!, deserializer!!, topic!!, eventCollection!!)
				.getAllKafkaRecords()
	}
}
