package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroDeserializer
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
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

abstract class KafkaRecordConsumer<T, R>(private val appConfiguration: AppConfiguration) {
	private val logger = LoggerFactory.getLogger(javaClass)


	fun getAllKafkaRecords(topic: String, valueDeserializer: Deserializer<T>): List<R> {
		try {
			logger.info("About to read records from $topic")

			KafkaConsumer<Key, T>(kafkaConfig(valueDeserializer))
				.use {
					val startTime = System.currentTimeMillis()
					it.subscribe(listOf(topic))

					val records = loopUntilKafkaRecordsAreRetrieved(it)

					logger.info("For topic $topic: Found ${records.size} records in ${System.currentTimeMillis() - startTime}ms")
					return records
				}

		} catch (e: Exception) {
			logger.error("For topic $topic: Error getting records", e)
			return emptyList()
		}
	}

	private fun loopUntilKafkaRecordsAreRetrieved(kafkaConsumer: KafkaConsumer<Key, T>): List<R> {

		val startTime = System.currentTimeMillis()
		val timeout = getTimeout()
		var hasReadRecords = false

		while (true) {
			val newRecords = retrieveKafkaRecords(kafkaConsumer)
			if (newRecords.isNotEmpty())
				hasReadRecords = true

			addRecords(newRecords)

			if (shouldStop(hasReadRecords, newRecords))
				break
			if (hasTimedOut(startTime, timeout, hasReadRecords)) {
				logger.warn("For topic ${kafkaConsumer.assignment()}: Was still consuming Kafka records " +
					"${System.currentTimeMillis() - startTime} ms after starting. Has read ${getRecords().size} records. " +
					"Aborting consumption.")
				break
			}
			if (newRecords.isEmpty())
				TimeUnit.MILLISECONDS.sleep(100)
		}
		return getRecords()
	}


	private fun hasTimedOut(startTime: Long, timeout: Int, hasReadRecords: Boolean): Boolean {

		val shouldEnforceTimeout = timeout > 0
		val hasTimedOut = System.currentTimeMillis() > startTime + timeout

		val hasTimedOutWithoutRecords = System.currentTimeMillis() > startTime + timeoutWhenNotFindingRecords

		return shouldEnforceTimeout && hasTimedOut || !hasReadRecords && hasTimedOutWithoutRecords
	}

	private fun retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<ConsumerRecord<Key, T>> {
		logger.info("Retrieving Kafka records for ${kafkaConsumer.assignment()}")
		val records = mutableListOf<ConsumerRecord<Key, T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		logger.info("Found ${consumerRecords.count()} consumerRecords for ${kafkaConsumer.assignment()}")
		for (record in consumerRecords) {

			if (record.key() != null && record.value() != null)
				records.add(record)
			else
				logger.error("For ${kafkaConsumer.assignment()}: Record had null attributes. " +
					"Key='${record.key()}', value ${if (record.value() == null) "is" else "is not"} null")
		}
		return records
	}


	private fun kafkaConfig(valueDeserializer: Deserializer<T>) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[ConsumerConfig.GROUP_ID_CONFIG] = getApplicationId()
		it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
		it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = valueDeserializer::class.java

		if (appConfiguration.kafkaConfig.secure == "TRUE") {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
			it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
			it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
		}
	}


	abstract fun getApplicationId(): String

	abstract fun getTimeout(): Int

	abstract fun shouldStop(hasPreviouslyReadRecords: Boolean, newRecords: List<*>): Boolean

	abstract fun addRecords(newRecords: List<ConsumerRecord<Key, T>>)

	abstract fun getRecords(): List<R>
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

typealias Key = String
private const val timeoutWhenNotFindingRecords = 30 * 1000
