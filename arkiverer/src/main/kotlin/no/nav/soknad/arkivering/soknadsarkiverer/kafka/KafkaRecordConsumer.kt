package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientConfig
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroDeserializer
import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.SslConfigs.*
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

abstract class KafkaRecordConsumer<T, R>(
	private val kafkaConfig: KafkaConfig,
	private val kafkaGroupId: String,
	private val valueDeserializer: Deserializer<T>,
	private val topic: String,
	private val clock: Clock = Clock()
) {

	private val logger = LoggerFactory.getLogger(javaClass)
	private val startTime = clock.currentTimeMillis()


	fun getAllKafkaRecords(): List<R> {
		try {
			logger.info("About to read records from $topic")

			createKafkaConsumer(kafkaConfig(valueDeserializer))
				.use {
					it.subscribe(listOf(topic))

					val records = loopUntilKafkaRecordsAreRetrieved(it)

					val timeTaken = clock.currentTimeMillis() - startTime
					logger.info("For topic $topic: Found ${records.size} relevant records in $timeTaken ms")
					return records
				}

		} catch (e: Exception) {
			logger.error("For topic $topic: Error getting records", e)
			return emptyList()
		}
	}

	private fun loopUntilKafkaRecordsAreRetrieved(kafkaConsumer: KafkaConsumer<Key, T>): List<R> {

		var timestampOfLastSuccessfulPoll = startTime
		var hasReadRecords = false

		while (true) {
			val newRecords = retrieveKafkaRecords(kafkaConsumer)
			if (newRecords.isNotEmpty()) {
				timestampOfLastSuccessfulPoll = clock.currentTimeMillis()
				hasReadRecords = true
			}

			addRecords(newRecords)

			if (shouldStop(newRecords))
				break
			if (hasTimedOut(timestampOfLastSuccessfulPoll, hasReadRecords)) {
				logger.warn(
					"For topic ${kafkaConsumer.assignment()}: Was still consuming Kafka records " +
						"${clock.currentTimeMillis() - startTime} ms after starting. Has read ${getRecords().size} records. " +
						"Aborting consumption."
				)
				break
			}
			if (newRecords.isEmpty())
				clock.sleep(sleepInMsBetweenFetches)
		}
		return getRecords()
	}


	private fun hasTimedOut(timestampOfLastPoll: Long, hasReadRecords: Boolean): Boolean {

		val timeout = getEnforcedTimeoutInMs()
		val shouldEnforceTimeout = timeout > 0
		val hasTimedOut = clock.currentTimeMillis() >= startTime + timeout

		val hasTimedOutWithoutRecords = clock.currentTimeMillis() >= startTime + timeoutWhenNotFindingRecords

		val hasTimedOutWithNoNewRecords = clock.currentTimeMillis() >= timestampOfLastPoll + timeoutWhenNotFindingNewRecords

		return (
			shouldEnforceTimeout && hasTimedOut ||
				!hasReadRecords && hasTimedOutWithoutRecords ||
				hasReadRecords && hasTimedOutWithNoNewRecords
			)
	}

	private fun retrieveKafkaRecords(kafkaConsumer: KafkaConsumer<Key, T>): List<ConsumerRecord<Key, T>> {
		logger.debug("Retrieving Kafka records for ${kafkaConsumer.assignment()}")
		val records = mutableListOf<ConsumerRecord<Key, T>>()

		val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1))
		logger.info("Found ${consumerRecords.count()} consumerRecords for ${kafkaConsumer.assignment()}")
		for (record in consumerRecords) {

			if (record.key() != null && record.value() != null)
				records.add(record)
			else
				logger.error(
					"For ${kafkaConsumer.assignment()}: Record had null attributes. " +
						"Key='${record.key()}', value ${if (record.value() == null) "is" else "is not"} null"
				)
		}
		return records
	}


	private fun kafkaConfig(valueDeserializer: Deserializer<T>) = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = kafkaConfig.schemaRegistry.url
		it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
		it[ConsumerConfig.GROUP_ID_CONFIG] = kafkaGroupId
		it[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = 5000
		it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
		it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
		it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = valueDeserializer::class.java

		if (kafkaConfig.security.enabled == "TRUE") {
			it[SchemaRegistryClientConfig.USER_INFO_CONFIG] = "${kafkaConfig.schemaRegistry.username}:${kafkaConfig.schemaRegistry.password}"
			it[SchemaRegistryClientConfig.BASIC_AUTH_CREDENTIALS_SOURCE] = "USER_INFO"
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = kafkaConfig.security.protocol
			it[SSL_KEYSTORE_TYPE_CONFIG] = "PKCS12"
			it[SSL_TRUSTSTORE_LOCATION_CONFIG] = kafkaConfig.security.trustStorePath
			it[SSL_TRUSTSTORE_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
			it[SSL_KEYSTORE_LOCATION_CONFIG] = kafkaConfig.security.keyStorePath
			it[SSL_KEYSTORE_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
			it[SSL_KEY_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
		}
	}

	internal open fun createKafkaConsumer(props: Properties) = KafkaConsumer<Key, T>(props)

	open fun shouldStop(newRecords: List<ConsumerRecord<Key, T>>) =
		newRecords.isNotEmpty() && newRecords.last().timestamp() > startTime


	abstract fun getEnforcedTimeoutInMs(): Int

	abstract fun addRecords(newRecords: List<ConsumerRecord<Key, T>>)

	abstract fun getRecords(): List<R>
}

/**
 * Used for fetching the current time and for sleeping. The purpose of putting this in its own class is to allow
 * tests to pass in a custom Clock.
 */
open class Clock {
	open fun sleep(millis: Long) {
		TimeUnit.MILLISECONDS.sleep(millis)
	}

	open fun currentTimeMillis() = System.currentTimeMillis()
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
			logger.error(
				"Unable to deserialize event on topic $topic\nByte Array: ${bytes.asList()}\n" +
					"String representation: '${String(bytes)}'", e
			)
			null
		}
	}
}

abstract class KafkaConsumerBuilder<T, R> {
	var kafkaConfig: KafkaConfig? = null
	var kafkaGroupId: String? = null
	var deserializer: Deserializer<T>? = null
	var topic: String? = null

	fun withKafkaConfig(appConfiguration: KafkaConfig) = apply { this.kafkaConfig = appConfiguration }
	fun withKafkaGroupId(kafkaGroupId: String) = apply { this.kafkaGroupId = kafkaGroupId }
	fun withValueDeserializer(deserializer: Deserializer<T>) = apply { this.deserializer = deserializer }
	fun forTopic(topic: String) = apply { this.topic = topic }

	abstract fun getAllKafkaRecords(): List<R>
}

typealias Key = String

const val sleepInMsBetweenFetches = 100L
const val timeoutWhenNotFindingRecords = 45 * 1000
const val timeoutWhenNotFindingNewRecords = 30 * 1000
