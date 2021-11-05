package no.nav.soknad.arkivering.soknadsarkiverer.kafka

import no.nav.soknad.arkivering.soknadsarkiverer.config.AppConfiguration
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecord.NULL_CHECKSUM
import org.apache.kafka.clients.consumer.ConsumerRecord.NULL_SIZE
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*

class KafkaRecordConsumerTests {
	private val clock = TestClock()
	private val consumerBuilder = ConsumerBuilder(clock)

	@AfterEach
	fun cleanup() {
		consumerBuilder.close()
	}


	@Test
	fun `Polling throws exception - empty list is returned`() {
		val result = consumerBuilder
			.mockPollThrowsException()
			.buildAndGetKafkaRecords()

		assertTrue(result.isEmpty())
	}

	@Test
	fun `Reads no records - times out after 30s`() {
		val result = consumerBuilder
			.mockPollReturnsNothing()
			.buildAndGetKafkaRecords()

		assertTrue(result.isEmpty())
		val actualTimeTaken = clock.currentTimeMillis() - clock.startTime - mockedTimeInMsForPolling
		assertEquals(timeoutWhenNotFindingRecords, actualTimeTaken.toInt(),
			"Should read for $timeoutWhenNotFindingRecords ms and then time out")
	}

	@Test
	fun `Reads records with null value`() {
		val result = consumerBuilder
			.mockPollReturnsRecordsInfinitely()
			.mockPollReturnsNullKeysAndValues()
			.buildAndGetKafkaRecords()

		assertTrue(result.isEmpty())
	}

	@Test
	fun `Reads two set of records but no more - times out 10s afterwards`() {
		val numberOfRecordsReturnedInEachPolling = listOf(0, 500, 71, 0)

		val result = consumerBuilder
			.mockPollReturnsRecordsOfGivenSizes(numberOfRecordsReturnedInEachPolling.asSequence())
			.buildAndGetKafkaRecords()

		assertEquals(numberOfRecordsReturnedInEachPolling.sum(), result.size)
		val timestampOfLastRead =
			mockedTimeInMsForPolling + sleepInMsBetweenFetches + // First poll returns 0 records => sleep
				mockedTimeInMsForPolling + // Second poll returns 500 records
				mockedTimeInMsForPolling  // Third poll returns 71 records
		val actualTimeTaken = clock.currentTimeMillis() - clock.startTime - timestampOfLastRead - sleepInMsBetweenFetches
		assertEquals(timeoutWhenNotFindingNewRecords, actualTimeTaken.toInt(),
			"Should read for $timeoutWhenNotFindingNewRecords ms and then time out")
	}

	@Test
	fun `Has gaps in between returned records`() {
		val numberOfRecordsReturnedInEachPolling = listOf(0, 68, 0, 71, 12, 0, 78,  0)

		val result = consumerBuilder
			.mockPollReturnsRecordsOfGivenSizes(numberOfRecordsReturnedInEachPolling.asSequence())
			.buildAndGetKafkaRecords()

		assertEquals(numberOfRecordsReturnedInEachPolling.sum(), result.size)
	}

	@Test
	fun `Reads records endlessly - enforces timeout`() {
		val timeoutInMs = 15 * 1000

		val result = consumerBuilder
			.mockPollReturnsRecordsInfinitely()
			.setEnforcedTimeout(timeoutInMs)
			.buildAndGetKafkaRecords()

		assertFalse(result.isEmpty())
		val actualTimeTaken = clock.currentTimeMillis() - clock.startTime - sleepInMsBetweenFetches
		assertEquals(timeoutInMs, actualTimeTaken.toInt(),
			"Should read for $timeoutInMs ms and then time out")
	}

	@Test
	fun `Reads records, stops when encountering record newer than start time of consumption`() {
		val magicNumber = 71
		val numberOfRecordsBeforeRecentTimestamp = (1 until magicNumber).sum()

		val result = consumerBuilder
			.mockPollReturnsRecordsInfinitely()
			.mockPollUsesTimestampsAfterGivenNumberOfRecords(clock.startTime - 10, numberOfRecordsBeforeRecentTimestamp)
			.buildAndGetKafkaRecords()

		assertEquals(numberOfRecordsBeforeRecentTimestamp + magicNumber, result.size)
	}

	@Test
	fun `Reads records, stops with custom logic`() {
		val magicNumber = 71
		val stopsWhenReturnedRecordsAreOfCertainSize = { records: List<*> -> records.size >= magicNumber }

		val result = consumerBuilder
			.mockPollReturnsRecordsInfinitely()
			.setCustomStopLogic(stopsWhenReturnedRecordsAreOfCertainSize)
			.buildAndGetKafkaRecords()

		assertEquals((0 .. magicNumber).sum(), result.size)
	}
}


/**
 * Boilerplate builder to make the tests read nicer.
 */
private class ConsumerBuilder(testClock: TestClock) {
	private val kafkaConsumer = MockKafkaConsumer(testClock)
	private val consumer = TestConsumer(kafkaConsumer, testClock)

	fun mockPollThrowsException(): ConsumerBuilder {
		kafkaConsumer.mockPollThrowsException()
		return this
	}

	fun mockPollReturnsNothing(): ConsumerBuilder {
		kafkaConsumer.mockPollReturnsSequence(sequenceOf(0))
		return this
	}

	fun mockPollReturnsRecordsInfinitely(): ConsumerBuilder {
		val infiniteSequence = generateSequence(0) { it + 1 }
		kafkaConsumer.mockPollReturnsSequence(infiniteSequence)
		return this
	}

	fun mockPollReturnsRecordsOfGivenSizes(sequence: Sequence<Int>): ConsumerBuilder {
		kafkaConsumer.mockPollReturnsSequence(sequence)
		return this
	}

	fun mockPollReturnsNullKeysAndValues(): ConsumerBuilder {
		kafkaConsumer.mockPollReturnsNullKeysAndValues()
		return this
	}

	fun mockPollUsesTimestampsAfterGivenNumberOfRecords(timestamp: Time, numberOfRecordsThatTriggers: Int): ConsumerBuilder {
		kafkaConsumer.setTimestampAfterNumberORecords(timestamp, numberOfRecordsThatTriggers)
		return this
	}

	fun setEnforcedTimeout(timeoutInMs: Int): ConsumerBuilder {
		consumer.setEnforcedTimeout(timeoutInMs)
		return this
	}

	fun setCustomStopLogic(stopLogic: (List<ConsumerRecord<Key, String>>) -> Boolean): ConsumerBuilder {
		consumer.setCustomStopLogic(stopLogic)
		return this
	}

	fun buildAndGetKafkaRecords() = consumer.getAllKafkaRecords()

	fun close() {
		kafkaConsumer.close()
	}
}

/**
 * A mocked version of a [KafkaConsumer]. The whole purpose of this class is to mock the behaviour of the [poll] method.
 * The class can be given a number of custom instructions that will affect the displayed behaviour.
 */
private class MockKafkaConsumer(private val clock: TestClock) : KafkaConsumer<Key, String>(kafkaProperties()) {
	private lateinit var numberOfRecordsReturnedInEachPollCall: Iterator<Int>
	private var kafkaTopicOffset = 0L
	private var customTimestamp = beginningOfTime
	private var numberOfRecordsThatTriggersTimestamps = 0
	private var throwException = false
	private var returnNullKeysAndValues = false

	fun mockPollThrowsException() {
		throwException = true
	}

	fun mockPollReturnsSequence(sequence: Sequence<Int>) {
		numberOfRecordsReturnedInEachPollCall = sequence.iterator()
	}

	fun mockPollReturnsNullKeysAndValues() {
		returnNullKeysAndValues = true
	}

	fun setTimestampAfterNumberORecords(timestamp: Time, numberOfRecordsThatTriggersCustomTimestamps: Int) {
		this.customTimestamp = timestamp
		this.numberOfRecordsThatTriggersTimestamps = numberOfRecordsThatTriggersCustomTimestamps
	}


	override fun poll(duration: Duration): ConsumerRecords<Key, String> {
		clock.stepForwardInTime(mockedTimeInMsForPolling)
		if (throwException)
			throw Exception("Mocked exception")

		val recordsToReturn = createRecords()
		return createConsumerRecords(recordsToReturn)
	}

	private fun createRecords(): List<ConsumerRecord<String, String>> {
		val numberOfRecordsToReturn = if (numberOfRecordsReturnedInEachPollCall.hasNext())
			numberOfRecordsReturnedInEachPollCall.next()
		else
			0

		val recordsToReturn = (0 until numberOfRecordsToReturn)
			.map { consumerRecord(kafkaTopicOffset + it, getTimestamp(it)) }
		kafkaTopicOffset += numberOfRecordsToReturn

		return recordsToReturn
	}

	private fun getTimestamp(i: Int): Time {
		val defaultTimestamp = beginningOfTime + kafkaTopicOffset + i

		return if (kafkaTopicOffset + 1 > numberOfRecordsThatTriggersTimestamps)
			customTimestamp + i
		else
			defaultTimestamp
	}

	private fun consumerRecord(offset: Long, timestamp: Time): ConsumerRecord<Key, String> {
		var key: String? = UUID.randomUUID().toString()
		var value: String? = UUID.randomUUID().toString()

		if (returnNullKeysAndValues) {
			when ((0 until 3).random()) {
				0 -> key = null
				1 -> value = null
				else -> { key = null; value = null }
			}
		}

		return ConsumerRecord(
			topic, 0, offset, timestamp, TimestampType.CREATE_TIME, NULL_CHECKSUM.toLong(),
			NULL_SIZE, NULL_SIZE, key, value
		)
	}

	/**
	 * Boilerplate.
	 */
	private fun createConsumerRecords(recordsToReturn: List<ConsumerRecord<String, String>>) =
		ConsumerRecords(mapOf(TopicPartition(topic, 0) to recordsToReturn))
}

/**
 * Boilerplate test class.
 */
private class TestConsumer(
	private val kafkaConsumer: KafkaConsumer<Key, String>,
	testClock: Clock
) : KafkaRecordConsumer<String, ConsumerRecord<Key, String>>(
	AppConfiguration(kafkaConfig()),
	"testId",
	StringDeserializer(),
	topic,
	testClock
) {
	private val recordsConsumed = mutableListOf<ConsumerRecord<Key, String>>()
	private var enforcedTimeout = 0
	private var stopLogic = { records: List<ConsumerRecord<Key, String>> -> super.shouldStop(records) }

	override fun createKafkaConsumer(props: Properties) = kafkaConsumer

	fun setEnforcedTimeout(millis: Int) {
		this.enforcedTimeout = millis
	}

	override fun getEnforcedTimeoutInMs() = enforcedTimeout

	fun setCustomStopLogic(stopLogic: (List<ConsumerRecord<Key, String>>) -> Boolean) {
		this.stopLogic = stopLogic
	}

	override fun shouldStop(newRecords: List<ConsumerRecord<Key, String>>) = stopLogic.invoke(newRecords)


	override fun addRecords(newRecords: List<ConsumerRecord<Key, String>>) {
		recordsConsumed.addAll(newRecords)
	}

	override fun getRecords() = recordsConsumed
}

/**
 * A clock that simulates sleeping, without actually pausing. Used to make the tests run fast.
 */
private class TestClock : Clock() {
	val startTime = System.currentTimeMillis()
	private var timeElapsed = 0L

	override fun currentTimeMillis() = startTime + timeElapsed

	override fun sleep(millis: Time) {
		stepForwardInTime(millis)
	}

	fun stepForwardInTime(millis: Time) {
		timeElapsed += millis
	}
}


/**
 * Boilerplate required by underlying libraries.
 */
private fun kafkaProperties() = Properties().also {
	it[ConsumerConfig.GROUP_ID_CONFIG] = kafkaConfig().groupId
	it[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig().servers
	it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
	it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
}

private fun kafkaConfig() =	AppConfiguration.KafkaConfig(
	"username", "password", "localhost:17171",	"localhost:16868", "FALSE",
	"SASL_PLAINTEXT", "PLAIN", "", topic, "processingTopic",
	"messageTopic", "metricsTopic", "0", "0",
	"testGroupId"
)

private typealias Time = Long
private const val topic = "testTopic"
private const val mockedTimeInMsForPolling = 500L
private const val beginningOfTime: Time = 0
