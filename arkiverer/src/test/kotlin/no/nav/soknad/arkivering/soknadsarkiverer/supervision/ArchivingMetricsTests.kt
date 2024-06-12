package no.nav.soknad.arkivering.soknadsarkiverer.supervision

import io.prometheus.metrics.core.datapoints.Timer
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ArchivingMetricsTests {

	private lateinit var metrics: ArchivingMetrics

	@BeforeEach
	fun setup() {
		metrics = ArchivingMetrics(PrometheusRegistry.defaultRegistry)
	}

	@AfterEach
	fun tearDown() {
		metrics.unregister()
	}

	@Test
	fun `test of counter`() {
		val noOfsucesses = 10
		repeat(noOfsucesses) {
			metrics.incJoarkSuccesses()
		}
		assertEquals(noOfsucesses.toDouble(), metrics.getJoarkSuccesses())
		assertEquals(0.0, metrics.getJoarkErrors())
	}

	@Test
	fun `test of gauge`() {
		repeat(3) {
			metrics.addTask()
		}
		repeat(2) {
			metrics.removeTask()
		}

		assertEquals(1.0, metrics.getTasks())
	}

	@Test
	fun `test of numberOfAttachmentsHistogram`() {
		val temaer = listOf("AAP", "TSO", "SYK")
		val numberOfAttachments = listOf(
			2, // 1
			3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, // 15
			5, 5, 5, 5, 5, // 5
			7, 7, 7, 7, // 4
			13, 13, 13, 13, 13, // 5
			21, 21, 21, 21, // 4
			34, // 1
			40 // 1
		)
		numberOfAttachments.forEach { metrics.setNumberOfAttachmentHistogram(it.toDouble(), temaer.random()) }
		val observations = mutableListOf<HistogramSnapshot.HistogramDataPointSnapshot?>()
		temaer.forEach { observations.add(metrics.getNumberOfAttachmentHistogram(it)) }

		assertTrue(observations.isNotEmpty())
		assertEquals(numberOfAttachments.size, observations.sumOf { it!!.count }.toInt())
		assertEquals(1, observations.sumOf { it!!.classicBuckets.getCount(0) })
		assertEquals(15, observations.sumOf { it!!.classicBuckets.getCount(1) })

	}

	@Test
	fun `test of filesizeHistogram`() {
		val temaer = listOf("AAP", "TSO", "SYK")
		val fileSizes = listOf(
			1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, // 15
			2 * 1024, // 1
			4 * 1024, // 1
			5 * 1024, // 1
			7 * 1024, // 1
			10 * 1024, 10 * 1024, 10 * 1024, 10 * 1024, 10 * 1024, // 5
			100 * 1024, 100 * 1024, 100 * 1024, 100 * 1024, // 4
			1024 * 1024, 1024 * 1024, 1024 * 1024, 1024 * 1024, 1024 * 1024, // 5
			10 * 1024 * 1024, 10 * 1024 * 1024, 10 * 1024 * 1024, 10 * 1024 * 1024, // 4
			50 * 1024 * 1024, 50 * 1024 * 1024, // 2
			100 * 1024 * 1024, // 1
			150 * 1024 * 1024, // 1
			200 * 1024 * 1024
		)
		fileSizes.forEach { metrics.setFileFetchSizeHistogram(it.toDouble(), temaer.random()) }
		val observations = mutableListOf<HistogramSnapshot.HistogramDataPointSnapshot?>()
		temaer.forEach { observations.add(metrics.getFileFetchSizeHistogram(it)) }

		assertTrue(observations.isNotEmpty())
		assertEquals(fileSizes.size, observations.sumOf { it!!.count }.toInt())

		assertEquals(15, observations.sumOf { it!!.classicBuckets.getCount(0) })
		assertEquals(1, observations.sumOf { it!!.classicBuckets.getCount(1) })
		assertEquals(1, observations.sumOf { it!!.classicBuckets.getCount(2) })
		assertEquals(2, observations.sumOf { it!!.classicBuckets.getCount(3) })
		assertEquals(5, observations.sumOf { it!!.classicBuckets.getCount(4) })
	}

	@Test
	fun `test of filSizeSummary`() {
		val fileSizes = listOf(
			1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, // 15
			10 * 1024, 10 * 1024, 10 * 1024, 10 * 1024, 10 * 1024, // 5
			100 * 1024, 100 * 1024, 100 * 1024, 100 * 1024, // 4
			1024 * 1024, 1024 * 1024, 1024 * 1024, 1024 * 1024, 1024 * 1024, // 5
			10 * 1024 * 1024, 10 * 1024 * 1024, 10 * 1024 * 1024, 10 * 1024 * 1024, // 4
			50 * 1024 * 1024, 50 * 1024 * 1024, // 2
			100 * 1024 * 1024, // 1
			150 * 1024 * 1024, // 1
			200 * 1024 * 1024
		)
		fileSizes.forEach { metrics.setFileFetchSize(it.toDouble()) }
		val observations = metrics.getFileFetchSize()

		assertTrue(observations != null)
		assertEquals(fileSizes.size, observations.sumOf { it.count }.toInt())
		assertEquals(200 * 1024 * 1024, observations[0].quantiles.last().value.toInt())
	}

	@Test
	fun `test av joarkLatencySummary`() {
		val latencies = mapOf(
			10L to 1,   // 1
			100L to 10, // 10
			500L to 3,  // 5
			1000L to 2, // 3
			10000L to 1 // 1
		)
		val timers = mutableListOf<Timer>()
		latencies.keys.forEach { repeat(latencies[it]!!) { timers.add(metrics.startJoarkLatency()) } }

		var nextTimer = 0
		var sleepTime = 10L
		nextTimer = sleepAndSetEndTimer(
			sleepTime = sleepTime,
			startIndex = nextTimer,
			latencyIndex = 10L,
			latencies = latencies,
			timers = timers
		)
		sleepTime = 100L - sleepTime
		nextTimer = sleepAndSetEndTimer(
			sleepTime = sleepTime,
			startIndex = nextTimer,
			latencyIndex = 100L,
			latencies = latencies,
			timers = timers
		)
		sleepTime = 500L - sleepTime
		nextTimer = sleepAndSetEndTimer(
			sleepTime = sleepTime,
			startIndex = nextTimer,
			latencyIndex = 500L,
			latencies = latencies,
			timers = timers
		)
		sleepTime = 1000L - sleepTime
		nextTimer = sleepAndSetEndTimer(
			sleepTime = sleepTime,
			startIndex = nextTimer,
			latencyIndex = 1000L,
			latencies = latencies,
			timers = timers
		)
		sleepTime = 10000L - sleepTime
		nextTimer = sleepAndSetEndTimer(
			sleepTime = sleepTime,
			startIndex = nextTimer,
			latencyIndex = 10000L,
			latencies = latencies,
			timers = timers
		)

		val observations = metrics.getJoarkLatency()
		assertEquals(latencies.values.sum().toDouble(), observations.sumOf { it.count }.toDouble())
		assertTrue(observations[0].quantiles[0]!!.value > 0.1)
		assertTrue(observations[0].quantiles[1]!!.value > 1.0)
		assertTrue(observations[0].quantiles[2]!!.value > 10.0)

	}

	private fun sleepAndSetEndTimer(
		sleepTime: Long,
		startIndex: Int,
		latencyIndex: Long,
		latencies: Map<Long, Int>,
		timers: List<Timer>
	): Int {
		Thread.sleep(sleepTime)
		var nextTimer = startIndex
		repeat(latencies[latencyIndex]!!) {
			metrics.endTimer(timers[nextTimer])
			nextTimer++
		}
		return nextTimer
	}
}
