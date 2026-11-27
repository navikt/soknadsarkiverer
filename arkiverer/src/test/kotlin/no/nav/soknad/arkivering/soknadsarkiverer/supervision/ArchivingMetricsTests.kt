package no.nav.soknad.arkivering.soknadsarkiverer.supervision

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
	fun `Joark latency is recorded in seconds with the configured quantiles`() {
		val numberOfCalls = 17
		val before = System.nanoTime()
		val timers = List(numberOfCalls) { metrics.startJoarkLatency() }
		timers.forEach { metrics.endTimer(it) }
		val elapsedSeconds = (System.nanoTime() - before) / 1_000_000_000.0

		val observations = metrics.getJoarkLatency().single()
		assertEquals(numberOfCalls.toLong(), observations.count)
		assertEquals(listOf(0.5, 0.9, 0.99), observations.quantiles.map { it.quantile })
		// Every timer ran inside the measured interval, so a value in milliseconds or nanoseconds would exceed it.
		assertTrue(observations.quantiles.all { it.value in 0.0..elapsedSeconds })
		assertTrue(observations.sum in 0.0..numberOfCalls * elapsedSeconds)
	}
}
